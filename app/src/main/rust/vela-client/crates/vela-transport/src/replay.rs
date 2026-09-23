//! Bounded first-flight (M1) replay suppression for TCP1 servers.
//!
//! Noise XK already rejects forged completions, but exact M1 replay still
//! elicits a fresh M2 and helps scanners fingerprint the service. This guard
//! is an implementation defense: it does not alter the frozen wire format.

use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Frozen VCP-1 M1 (`CLIENT_HELLO`) plaintext length inside the outer record.
const M1_LEN: usize = 50;

/// Default retention window for observed M1 flights.
pub const DEFAULT_REPLAY_TTL: Duration = Duration::from_secs(5 * 60);

/// Default maximum number of remembered M1 payloads.
pub const DEFAULT_REPLAY_CAPACITY: usize = 4_096;

/// Shared, process-local cache of recently observed handshake M1 payloads.
#[derive(Debug)]
pub struct HandshakeReplayGuard {
    inner: Mutex<Inner>,
}

#[derive(Debug)]
struct Inner {
    ttl: Duration,
    capacity: usize,
    order: VecDeque<([u8; M1_LEN], Instant)>,
    seen: HashMap<[u8; M1_LEN], Instant>,
}

impl HandshakeReplayGuard {
    /// Builds a guard with the given retention window and capacity.
    #[must_use]
    pub fn new(ttl: Duration, capacity: usize) -> Self {
        Self {
            inner: Mutex::new(Inner {
                ttl,
                capacity: capacity.max(1),
                order: VecDeque::new(),
                seen: HashMap::new(),
            }),
        }
    }

    /// Production defaults: five-minute TTL and 4096 remembered flights.
    #[must_use]
    pub fn production() -> Self {
        Self::new(DEFAULT_REPLAY_TTL, DEFAULT_REPLAY_CAPACITY)
    }

    /// Returns `true` when `m1` is new and has been recorded; `false` on replay.
    /// The caller must validate M1 cryptographically before admission, so forged
    /// flights cannot evict validated entries from this bounded cache.
    ///
    /// Non-canonical lengths are treated as non-replay so the normal handshake
    /// path can fail closed without writing an M2.
    pub fn admit(&self, m1: &[u8]) -> bool {
        let Ok(key) = <[u8; M1_LEN]>::try_from(m1) else {
            return true;
        };
        let Ok(mut guard) = self.inner.lock() else {
            // A poisoned mutex must not open a response surface; refuse.
            return false;
        };
        let now = Instant::now();
        guard.evict_expired(now);
        if let Some(seen_at) = guard.seen.get(&key) {
            if now.duration_since(*seen_at) <= guard.ttl {
                return false;
            }
        }
        while guard.order.len() >= guard.capacity {
            if let Some((old, _)) = guard.order.pop_front() {
                guard.seen.remove(&old);
            }
        }
        guard.order.push_back((key, now));
        guard.seen.insert(key, now);
        true
    }

    /// Test helper: number of currently retained flights.
    #[cfg(test)]
    pub fn len_for_test(&self) -> usize {
        self.inner.lock().map(|g| g.seen.len()).unwrap_or(0)
    }
}

impl Default for HandshakeReplayGuard {
    fn default() -> Self {
        Self::production()
    }
}

impl Inner {
    fn evict_expired(&mut self, now: Instant) {
        while let Some((key, seen_at)) = self.order.front().copied() {
            if now.duration_since(seen_at) <= self.ttl {
                break;
            }
            self.order.pop_front();
            if let Some(stored) = self.seen.get(&key).copied() {
                if stored == seen_at {
                    self.seen.remove(&key);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn admits_unique_m1_and_rejects_exact_replay() {
        let guard = HandshakeReplayGuard::new(Duration::from_secs(60), 8);
        let m1 = [7u8; M1_LEN];
        assert!(guard.admit(&m1));
        assert!(!guard.admit(&m1));
        assert_eq!(guard.len_for_test(), 1);
    }

    #[test]
    fn capacity_evicts_oldest_entries() {
        let guard = HandshakeReplayGuard::new(Duration::from_secs(60), 2);
        assert!(guard.admit(&[1u8; M1_LEN]));
        assert!(guard.admit(&[2u8; M1_LEN]));
        assert!(guard.admit(&[3u8; M1_LEN]));
        assert_eq!(guard.len_for_test(), 2);
        // Oldest (all-1s) was evicted and may be admitted again.
        assert!(guard.admit(&[1u8; M1_LEN]));
    }

    #[test]
    fn concurrent_duplicates_have_one_winner() {
        let guard = std::sync::Arc::new(HandshakeReplayGuard::default());
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(16));
        let workers: Vec<_> = (0..16)
            .map(|_| {
                let guard = std::sync::Arc::clone(&guard);
                let barrier = std::sync::Arc::clone(&barrier);
                std::thread::spawn(move || {
                    barrier.wait();
                    guard.admit(&[9; M1_LEN])
                })
            })
            .collect();
        let winners = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .filter(|accepted| *accepted)
            .count();
        assert_eq!(winners, 1);
        assert_eq!(guard.len_for_test(), 1);
    }

    #[test]
    fn expired_entry_can_be_admitted_again() {
        let guard = HandshakeReplayGuard::new(Duration::from_millis(1), 1);
        assert!(guard.admit(&[4; M1_LEN]));
        std::thread::sleep(Duration::from_millis(20));
        assert!(guard.admit(&[4; M1_LEN]));
        assert_eq!(guard.len_for_test(), 1);
    }

    #[test]
    fn sustained_admit_storm_stays_strictly_bounded() {
        // Attacker model: 100k unique first-flights hammer the guard. Memory
        // must stay capped; admission itself never panics.
        let guard = HandshakeReplayGuard::new(Duration::from_secs(300), 64);
        let mut key = [0u8; M1_LEN];
        for i in 0..100_000u32 {
            key[..4].copy_from_slice(&i.to_le_bytes());
            assert!(guard.admit(&key));
        }
        assert!(guard.len_for_test() <= 64);
        // An evicted first-flight is simply treated as new (full handshake
        // still required): fail-closed, never an error or panic.
        key[..4].copy_from_slice(&0u32.to_le_bytes());
        assert!(guard.admit(&key));
        assert!(guard.len_for_test() <= 64);
    }

    #[test]
    fn duplicate_storm_keeps_single_entry() {
        let guard = HandshakeReplayGuard::new(Duration::from_secs(300), 64);
        assert!(guard.admit(&[11; M1_LEN]));
        for _ in 0..10_000 {
            assert!(!guard.admit(&[11; M1_LEN]));
        }
        assert_eq!(guard.len_for_test(), 1);
    }
}
