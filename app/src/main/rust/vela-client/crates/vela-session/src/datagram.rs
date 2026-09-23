//! Vela datagram context lifecycle (spec §12).
//!
//! Invariants:
//! - `ContextID` is `u32`; `0` is reserved; client IDs are odd, server IDs are
//!   even; IDs strictly increase per initiator and are never reused.
//! - A context is OPENING after `DATAGRAM_OPEN`, becomes ACTIVE only after
//!   `DATAGRAM_OPEN_RESULT(OK)`, and becomes CLOSED after a failed result or
//!   `DATAGRAM_CLOSE`.
//! - `DATAGRAM_DATA` is legal only for ACTIVE contexts; zero-length payloads
//!   are legal.
//! - A closed `ContextID` is never reusable.
//! - Unknown nonzero `DATAGRAM_OPEN_RESULT` status is failure.

use std::collections::HashMap;

use vela_proto::{ContextId, DatagramCloseReason, OpenResultStatus};

use crate::connection::Role;
use crate::error::SessionError;
use crate::ids::ContextIdAllocator;

/// Maximum number of closed-context tombstones retained per session.
///
/// Datagram close paths release entries immediately (no racing frame may
/// legally follow a `CLOSE` or a failed `OPEN_RESULT`), so in practice this
/// table holds only live contexts. The constant documents the same bound
/// style as the stream table; worst case stays well under one megabyte.
pub const MAX_RETAINED_CLOSED_DATAGRAM: usize = 1_024;

/// Lifecycle state of a single datagram context.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DatagramState {
    /// `DATAGRAM_OPEN` sent or received; awaiting `DATAGRAM_OPEN_RESULT`.
    Opening,
    /// `DATAGRAM_DATA` is legal.
    Active,
    /// Terminal: closed by failure or `DATAGRAM_CLOSE`; never reused.
    Closed,
}

impl DatagramState {
    /// Returns `true` when `DATAGRAM_DATA` is legal.
    #[must_use]
    pub const fn can_send_data(self) -> bool {
        matches!(self, Self::Active)
    }
}

/// Table of all datagram contexts known to a session endpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DatagramTable {
    role: Role,
    max_concurrent: u32,
    ids: ContextIdAllocator,
    contexts: HashMap<u32, DatagramState>,
}

impl DatagramTable {
    /// Creates an empty datagram table for the given role and ceiling.
    #[must_use]
    pub fn new(role: Role, max_concurrent: u32) -> Self {
        Self {
            role,
            max_concurrent,
            ids: ContextIdAllocator::new(role),
            contexts: HashMap::new(),
        }
    }

    /// Returns the number of contexts that are not yet closed.
    ///
    /// Closed contexts are released immediately (see [`Self::retained_count`]),
    /// so this equals the number of retained entries.
    #[must_use]
    pub fn open_count(&self) -> u32 {
        self.contexts.len().try_into().unwrap_or(u32::MAX)
    }

    /// Total retained entries. Invariant: always equals `open_count()`,
    /// because fully closed contexts are released at once — a peer cannot
    /// grow this table without bound.
    #[must_use]
    pub fn retained_count(&self) -> usize {
        self.contexts.len()
    }

    /// Returns the state of a known context, or `None` if unknown.
    #[must_use]
    pub fn state(&self, id: ContextId) -> Option<DatagramState> {
        self.contexts.get(&id.get()).copied()
    }

    /// Opens a locally initiated datagram context, emitting a new `ContextID`.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] when the concurrent-context ceiling is
    /// reached or the identifier space is exhausted.
    pub fn open(&mut self) -> Result<ContextId, SessionError> {
        if self.open_count() >= self.max_concurrent {
            return Err(SessionError::ResourceLimit("too many concurrent contexts"));
        }
        let id = self.ids.allocate()?;
        self.contexts.insert(id.get(), DatagramState::Opening);
        Ok(id)
    }

    /// Registers a peer-initiated `DATAGRAM_OPEN` and returns its validated ID.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] on parity or monotonicity violations,
    /// [`SessionError::ResourceLimit`] at the ceiling.
    pub fn on_peer_open(&mut self, id: ContextId) -> Result<ContextId, SessionError> {
        self.ids.validate_peer(id)?;
        if self.open_count() >= self.max_concurrent {
            return Err(SessionError::ResourceLimit("too many concurrent contexts"));
        }
        self.contexts.insert(id.get(), DatagramState::Opening);
        Ok(id)
    }

    /// Processes `DATAGRAM_OPEN_RESULT` for a context.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the context is unknown.
    pub fn on_open_result(
        &mut self,
        id: ContextId,
        status: OpenResultStatus,
    ) -> Result<(), SessionError> {
        let key = id.get();
        if !self.contexts.contains_key(&key) {
            return Err(SessionError::ProtocolViolation("unknown context id"));
        }
        // Unknown nonzero status values are failure, never success (spec §12).
        if status.is_success() {
            self.contexts.insert(key, DatagramState::Active);
        } else {
            // A failed open ends the context with no legal racing frame:
            // release the entry instead of retaining a tombstone.
            self.contexts.remove(&key);
        }
        Ok(())
    }

    /// Validates a `DATAGRAM_DATA` send on an ACTIVE context.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the context is unknown or not
    /// ACTIVE.
    pub fn send_data(&self, id: ContextId, _payload: &[u8]) -> Result<(), SessionError> {
        let state = self
            .contexts
            .get(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown context id"))?;
        if !state.can_send_data() {
            return Err(SessionError::ProtocolViolation(
                "datagram data is not allowed in this state",
            ));
        }
        Ok(())
    }

    /// Applies `DATAGRAM_CLOSE`: terminal for the context.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the context is unknown or
    /// already closed.
    pub fn close(
        &mut self,
        id: ContextId,
        _reason: DatagramCloseReason,
    ) -> Result<(), SessionError> {
        // `CLOSE` is terminal with no legal racing frame: release the entry.
        // A duplicate close fails closed as `unknown context id`.
        self.contexts.remove(&id.get()).map_or(
            Err(SessionError::ProtocolViolation("unknown context id")),
            |_| Ok(()),
        )
    }
}
