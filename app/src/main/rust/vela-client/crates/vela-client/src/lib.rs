#![forbid(unsafe_code)]
//! Client orchestration boundary: synchronous connection pool over established
//! Vela sessions.
//!
//! `vela-client` provides a bounded, reusable pool of `TcpSessionDriver`
//! connections keyed by server endpoint and static public key. The pool is
//! thread-safe and hands out exclusive mutable access via a guard that returns
//! the connection when dropped.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::ops::{Deref, DerefMut};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

pub use vela_crypto::keys::{ClientStaticPrivateKey, ClientStaticPublicKey, ServerStaticPublicKey};
use vela_session::SessionConfig;
use vela_transport::TransportError;
use vela_transport::tcp::{ConnectConfig, TcpSessionDriver, connect_client};

/// Default maximum connections per pool key.
const DEFAULT_MAX_SIZE: usize = 8;

/// Marker for the client orchestration boundary.
#[derive(Debug, Default, Clone, Copy)]
pub struct ClientBoundary;

pub mod socks5;
pub use socks5::run_socks5_proxy;

/// Logical identity of a pooled server endpoint.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct PoolKey {
    server_addr: SocketAddr,
    server_public_key: ServerStaticPublicKey,
}

impl PoolKey {
    fn new(server_addr: SocketAddr, server_public_key: ServerStaticPublicKey) -> Self {
        Self {
            server_addr,
            server_public_key,
        }
    }
}

#[derive(Debug)]
struct PoolState {
    max_size: usize,
    available: HashMap<PoolKey, Vec<TcpSessionDriver>>,
    checked_out: HashMap<PoolKey, usize>,
}

impl PoolState {
    fn total_for(&self, key: &PoolKey) -> usize {
        let available = self.available.get(key).map_or(0, Vec::len);
        let checked_out = self.checked_out.get(key).copied().unwrap_or(0);
        available + checked_out
    }
}

/// Synchronous, bounded connection pool for Vela client sessions.
#[derive(Debug, Clone)]
pub struct ConnectionPool {
    state: Arc<Mutex<PoolState>>,
    not_empty: Arc<Condvar>,
    connect_timeout: Duration,
    handshake_timeout: Duration,
    acquire_timeout: Duration,
}

impl ConnectionPool {
    /// Returns a builder for configuring the pool.
    #[must_use]
    pub fn builder() -> ConnectionPoolBuilder {
        ConnectionPoolBuilder::new()
    }

    /// Returns the number of connections currently idle in the pool.
    ///
    /// # Panics
    /// Panics if the pool mutex is poisoned.
    #[must_use]
    pub fn len(&self) -> usize {
        let state = self.state.lock().expect("pool mutex poisoned");
        state.available.values().map(Vec::len).sum()
    }

    /// Returns `true` when no idle connections are currently in the pool.
    ///
    /// # Panics
    /// Panics if the pool mutex is poisoned.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Returns the number of connections currently checked out.
    ///
    /// # Panics
    /// Panics if the pool mutex is poisoned.
    #[must_use]
    pub fn checked_out(&self) -> usize {
        let state = self.state.lock().expect("pool mutex poisoned");
        state.checked_out.values().sum()
    }

    /// Acquires an exclusive connection to the given server, creating a new one
    /// if necessary and permitted by `max_size`. If the pool is at capacity and
    /// all connections are in use, this call blocks until one is returned.
    ///
    /// # Errors
    /// Returns `TransportError` if a new connection cannot be established.
    ///
    /// # Panics
    /// Panics if the pool mutex is poisoned.
    pub fn acquire(
        &self,
        server_addr: SocketAddr,
        server_public_key: &ServerStaticPublicKey,
        client_static: &ClientStaticPrivateKey,
        config: SessionConfig,
    ) -> Result<PooledConnection, TransportError> {
        let key = PoolKey::new(server_addr, *server_public_key);

        let mut state = self.state.lock().expect("pool mutex poisoned");
        loop {
            // Return an idle connection if one exists.
            if let Some(entry) = state.available.get_mut(&key).and_then(Vec::pop) {
                *state.checked_out.entry(key).or_insert(0) += 1;
                return Ok(PooledConnection {
                    driver: Some(entry),
                    key,
                    pool: self.state.clone(),
                    not_empty: self.not_empty.clone(),
                    broken: false,
                });
            }

            // Reserve a slot, then release the lock for the network handshake.
            if state.total_for(&key) < state.max_size {
                *state.checked_out.entry(key).or_insert(0) += 1;
                drop(state);
                match self.connect(key, client_static, config) {
                    Ok(driver) => {
                        return Ok(PooledConnection {
                            driver: Some(driver),
                            key,
                            pool: self.state.clone(),
                            not_empty: self.not_empty.clone(),
                            broken: false,
                        });
                    }
                    Err(error) => {
                        let mut state = self.state.lock().expect("pool mutex poisoned");
                        if let Some(count) = state.checked_out.get_mut(&key) {
                            *count = count.saturating_sub(1);
                        }
                        self.not_empty.notify_one();
                        return Err(error);
                    }
                }
            }

            // At capacity: wait for a connection to be returned.
            let (next, timed_out) = self
                .not_empty
                .wait_timeout(state, self.acquire_timeout)
                .expect("pool mutex poisoned");
            state = next;
            if timed_out.timed_out() && state.available.get(&key).is_none_or(Vec::is_empty) {
                return Err(TransportError::AcquireTimeout);
            }
        }
    }

    fn connect(
        &self,
        key: PoolKey,
        client_static: &ClientStaticPrivateKey,
        config: SessionConfig,
    ) -> Result<TcpSessionDriver, TransportError> {
        let connect_config = ConnectConfig {
            server_addr: key.server_addr,
            connect_timeout: self.connect_timeout,
            handshake_timeout: self.handshake_timeout,
        };
        connect_client(
            &connect_config,
            client_static,
            &key.server_public_key,
            config,
        )
    }
}

/// RAII guard around a pooled connection. Hands the driver back to the pool on
/// drop unless it has been marked broken or is no longer healthy.
pub struct PooledConnection {
    driver: Option<TcpSessionDriver>,
    key: PoolKey,
    pool: Arc<Mutex<PoolState>>,
    not_empty: Arc<Condvar>,
    broken: bool,
}

impl PooledConnection {
    /// Marks the connection as broken so it will be closed instead of returned
    /// to the pool when this guard is dropped.
    pub fn mark_broken(&mut self) {
        self.broken = true;
    }
}

impl Deref for PooledConnection {
    type Target = TcpSessionDriver;

    fn deref(&self) -> &Self::Target {
        self.driver.as_ref().expect("pooled driver present")
    }
}

impl DerefMut for PooledConnection {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.driver.as_mut().expect("pooled driver present")
    }
}

impl Drop for PooledConnection {
    fn drop(&mut self) {
        let driver = self.driver.take().expect("pooled driver present");
        let mut state = self.pool.lock().expect("pool mutex poisoned");

        let checked_out = state
            .checked_out
            .get_mut(&self.key)
            .expect("checked-out count");
        *checked_out = checked_out.saturating_sub(1);

        if self.broken || !driver.is_healthy() {
            drop(driver);
        } else {
            state.available.entry(self.key).or_default().push(driver);
        }

        self.not_empty.notify_one();
    }
}

/// Builder for [`ConnectionPool`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ConnectionPoolBuilder {
    max_size: usize,
    connect_timeout: Duration,
    handshake_timeout: Duration,
    acquire_timeout: Duration,
}

impl ConnectionPoolBuilder {
    fn new() -> Self {
        Self {
            max_size: DEFAULT_MAX_SIZE,
            connect_timeout: Duration::from_secs(5),
            handshake_timeout: Duration::from_secs(5),
            acquire_timeout: Duration::from_secs(30),
        }
    }

    /// Sets the maximum number of connections retained per server key.
    #[must_use]
    pub fn max_size(mut self, size: usize) -> Self {
        self.max_size = size.max(1);
        self
    }

    /// Sets the TCP connect timeout for new connections.
    #[must_use]
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.connect_timeout = timeout;
        self
    }

    /// Sets the Noise handshake timeout for new connections.
    #[must_use]
    pub fn handshake_timeout(mut self, timeout: Duration) -> Self {
        self.handshake_timeout = timeout;
        self
    }

    /// Sets how long `acquire` waits when the pool is at capacity.
    #[must_use]
    pub fn acquire_timeout(mut self, timeout: Duration) -> Self {
        self.acquire_timeout = timeout;
        self
    }

    /// Builds the pool.
    #[must_use]
    pub fn build(self) -> ConnectionPool {
        ConnectionPool {
            state: Arc::new(Mutex::new(PoolState {
                max_size: self.max_size,
                available: HashMap::new(),
                checked_out: HashMap::new(),
            })),
            not_empty: Arc::new(Condvar::new()),
            connect_timeout: self.connect_timeout,
            handshake_timeout: self.handshake_timeout,
            acquire_timeout: self.acquire_timeout,
        }
    }
}

/// Builds a pool so the `vela` binary can prove the client runtime links.
#[must_use]
pub fn smoke_runtime() -> ConnectionPool {
    ConnectionPool::builder().max_size(1).build()
}
