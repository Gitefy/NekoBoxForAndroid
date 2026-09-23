//! Vela identifier allocators and peer identifier validation.
//!
//! Invariants (spec §10, §12, §13, §15):
//! - Identifiers are nonzero; `0` is reserved and unrepresentable.
//! - Client-initiated identifiers are odd; server-initiated identifiers are even.
//! - Identifiers are strictly increasing per initiator.
//! - Identifiers are never reused within a session.
//! - Exhaustion is terminal (fail-closed): no wrap-around reuse is permitted.
//!
//! Monotonicity structurally implies non-reuse, so peer identifiers are
//! validated solely by parity plus strict monotonicity.

use std::collections::HashSet;

use vela_proto::{ContextId, DnsRequestId, StreamId};

use crate::connection::Role;
use crate::error::SessionError;

/// Parity step for identifier sequences: client odd (starts at 1), server even
/// (starts at 2).
const fn initial_value(role: Role) -> u32 {
    match role {
        Role::Client => 1,
        Role::Server => 2,
    }
}

const fn matches_role_parity(role: Role, value: u32) -> bool {
    match role {
        Role::Client => value % 2 == 1,
        Role::Server => value % 2 == 0,
    }
}

/// Allocates and validates `StreamID` values (spec §10).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamIdAllocator {
    role: Role,
    next: u32,
    last_peer: u32,
    exhausted: bool,
}

impl StreamIdAllocator {
    /// Creates an allocator for the given role.
    #[must_use]
    pub const fn new(role: Role) -> Self {
        Self {
            role,
            next: initial_value(role),
            // No peer identifier has been observed yet; identifiers are
            // nonzero, so the first valid peer identifier is always > 0.
            last_peer: 0,
            exhausted: false,
        }
    }

    /// Returns the next local `StreamID` value without allocating it.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] once the identifier space is exhausted.
    pub fn peek(&self) -> Result<u32, SessionError> {
        if self.exhausted {
            return Err(SessionError::ResourceLimit("stream id space exhausted"));
        }
        Ok(self.next)
    }

    /// Allocates the next local `StreamID`.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] once the identifier space is exhausted
    /// (terminal; identifiers are never reused).
    pub fn allocate(&mut self) -> Result<StreamId, SessionError> {
        if self.exhausted {
            return Err(SessionError::ResourceLimit("stream id space exhausted"));
        }
        let value = self.next;
        match value.checked_add(2) {
            Some(n) => self.next = n,
            // The current value is the last one of its parity: issue it once,
            // then refuse forever rather than wrapping and reusing IDs.
            None => self.exhausted = true,
        }
        StreamId::new(value).ok_or(SessionError::ResourceLimit("stream id space exhausted"))
    }

    /// Validates a peer-supplied `StreamID` for parity and strict monotonicity.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] on parity violation, non-increasing
    /// value, or reuse.
    pub fn validate_peer(&mut self, id: StreamId) -> Result<(), SessionError> {
        let value = id.get();
        if !matches_role_parity(self.role.peer(), value) {
            return Err(SessionError::ProtocolViolation(
                "stream id parity violation",
            ));
        }
        if value <= self.last_peer {
            return Err(SessionError::ProtocolViolation(
                "stream id is not increasing",
            ));
        }
        self.last_peer = value;
        Ok(())
    }

    /// Test-only hook: drives the allocator close to exhaustion.
    #[cfg(test)]
    pub(crate) fn force_next_for_testing(&mut self, next: u32) {
        self.next = next;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The final identifier of the correct parity is issued exactly once; the
    /// allocator then refuses forever instead of wrapping and reusing IDs.
    #[test]
    fn stream_id_exhaustion_is_terminal_after_last_value() {
        let mut alloc = StreamIdAllocator::new(Role::Client);
        alloc.force_next_for_testing(0xFFFF_FFFF);
        assert_eq!(alloc.allocate().expect("last usable id").get(), 0xFFFF_FFFF);
        assert_eq!(
            alloc.allocate(),
            Err(SessionError::ResourceLimit("stream id space exhausted"))
        );
        assert_eq!(
            alloc.allocate(),
            Err(SessionError::ResourceLimit("stream id space exhausted"))
        );
    }

    #[test]
    fn context_id_exhaustion_is_terminal_after_last_value() {
        let mut alloc = ContextIdAllocator::new(Role::Server);
        alloc.force_next_for_testing(0xFFFF_FFFE);
        assert_eq!(alloc.allocate().expect("last usable id").get(), 0xFFFF_FFFE);
        assert_eq!(
            alloc.allocate(),
            Err(SessionError::ResourceLimit("context id space exhausted"))
        );
    }

    #[test]
    fn dns_request_id_exhaustion_is_terminal_after_last_value() {
        let mut alloc = DnsRequestIdAllocator::new();
        alloc.force_next_for_testing(0xFFFF_FFFF);
        assert_eq!(alloc.allocate().expect("last usable id").get(), 0xFFFF_FFFF);
        assert_eq!(
            alloc.allocate(),
            Err(SessionError::ResourceLimit(
                "dns request id space exhausted"
            ))
        );
    }

    /// Monotonic allocation structurally prevents reuse over a long run.
    #[test]
    fn allocated_ids_never_repeat() {
        let mut alloc = StreamIdAllocator::new(Role::Client);
        let mut seen = HashSet::new();
        for _ in 0..1000 {
            let id = alloc.allocate().expect("id");
            assert!(seen.insert(id.get()), "duplicate id {id:?}");
        }
    }
}

/// Allocates and validates `ContextID` values (spec §12).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContextIdAllocator {
    role: Role,
    next: u32,
    last_peer: u32,
    exhausted: bool,
}

impl ContextIdAllocator {
    /// Creates an allocator for the given role.
    #[must_use]
    pub const fn new(role: Role) -> Self {
        Self {
            role,
            next: initial_value(role),
            // No peer identifier has been observed yet; identifiers are
            // nonzero, so the first valid peer identifier is always > 0.
            last_peer: 0,
            exhausted: false,
        }
    }

    /// Allocates the next local `ContextID`.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] on exhaustion (terminal).
    pub fn allocate(&mut self) -> Result<ContextId, SessionError> {
        if self.exhausted {
            return Err(SessionError::ResourceLimit("context id space exhausted"));
        }
        let value = self.next;
        match value.checked_add(2) {
            Some(n) => self.next = n,
            None => self.exhausted = true,
        }
        ContextId::new(value).ok_or(SessionError::ResourceLimit("context id space exhausted"))
    }

    /// Validates a peer-supplied `ContextID`.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] on parity violation or reuse.
    pub fn validate_peer(&mut self, id: ContextId) -> Result<(), SessionError> {
        let value = id.get();
        if !matches_role_parity(self.role.peer(), value) {
            return Err(SessionError::ProtocolViolation(
                "context id parity violation",
            ));
        }
        if value <= self.last_peer {
            return Err(SessionError::ProtocolViolation(
                "context id is not increasing",
            ));
        }
        self.last_peer = value;
        Ok(())
    }

    /// Test-only hook: drives the allocator close to exhaustion.
    #[cfg(test)]
    pub(crate) fn force_next_for_testing(&mut self, next: u32) {
        self.next = next;
    }
}

/// Allocates `DNSRequestID` values that are unique while outstanding (spec §13).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsRequestIdAllocator {
    next: u32,
    outstanding: HashSet<u32>,
    exhausted: bool,
}

impl DnsRequestIdAllocator {
    /// Creates a new allocator.
    #[must_use]
    pub fn new() -> Self {
        Self {
            next: 1,
            outstanding: HashSet::new(),
            exhausted: false,
        }
    }

    /// Allocates the next `DNSRequestID` and marks it outstanding.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] on exhaustion (terminal).
    pub fn allocate(&mut self) -> Result<DnsRequestId, SessionError> {
        if self.exhausted {
            return Err(SessionError::ResourceLimit(
                "dns request id space exhausted",
            ));
        }
        let value = self.next;
        match value.checked_add(1) {
            Some(n) => self.next = n,
            None => self.exhausted = true,
        }
        let id = DnsRequestId::new(value).ok_or(SessionError::ResourceLimit(
            "dns request id space exhausted",
        ))?;
        self.outstanding.insert(value);
        Ok(id)
    }

    /// Returns `true` while the request is awaiting a response.
    #[must_use]
    pub fn is_outstanding(&self, id: DnsRequestId) -> bool {
        self.outstanding.contains(&id.get())
    }

    /// Completes an outstanding request, releasing its identifier guarantee.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the identifier is not
    /// outstanding (no matching request, or already completed).
    pub fn complete(&mut self, id: DnsRequestId) -> Result<(), SessionError> {
        if self.outstanding.remove(&id.get()) {
            Ok(())
        } else {
            Err(SessionError::ProtocolViolation(
                "dns request id is not outstanding",
            ))
        }
    }

    /// Test-only hook: drives the allocator close to exhaustion.
    #[cfg(test)]
    pub(crate) fn force_next_for_testing(&mut self, next: u32) {
        self.next = next;
    }
}

impl Default for DnsRequestIdAllocator {
    fn default() -> Self {
        Self::new()
    }
}

impl Role {
    /// Returns the opposite role.
    #[must_use]
    pub const fn peer(self) -> Self {
        match self {
            Self::Client => Self::Server,
            Self::Server => Self::Client,
        }
    }
}
