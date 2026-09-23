//! Partitioned error taxonomy for the Vela session layer.
//!
//! Mirrors the `vela-crypto` partition:
//! - **Recoverable local errors**: caller parameter mistakes detected before
//!   any state mutation. The caller may retry with corrected input.
//! - **Terminal protocol errors**: irreversible violations. The connection is
//!   terminated; there is no fallback, no degraded mode, and no attempt to
//!   guess the peer's intent (spec §15, §16).

use crate::connection::ConnectionState;

/// Errors produced by the Vela session layer.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SessionError {
    // -------------------------------------------------------------------
    // RECOVERABLE LOCAL ERRORS (parameter checks before state mutation)
    // -------------------------------------------------------------------
    /// A session configuration value was invalid.
    #[error("invalid session configuration: {field}")]
    InvalidConfiguration {
        /// Name of the offending configuration field.
        field: &'static str,
    },

    /// Local flow-control backpressure: the peer has not yet granted enough
    /// credit. This is a normal send-side condition, **not** a protocol
    /// error; the caller waits for credit and retries.
    #[error("insufficient flow-control credit (backpressure)")]
    Backpressure,

    // -------------------------------------------------------------------
    // TERMINAL PROTOCOL ERRORS (irreversible; connection is terminated)
    // -------------------------------------------------------------------
    /// A state-machine transition was not legal from the current state.
    #[error("invalid state transition: {from:?} -> {to:?}")]
    InvalidStateTransition {
        /// State the transition was attempted from.
        from: ConnectionState,
        /// State the transition attempted to reach.
        to: ConnectionState,
    },

    /// A normative protocol rule was violated.
    #[error("protocol violation: {0}")]
    ProtocolViolation(&'static str),

    /// An operation was attempted by the wrong role.
    #[error("role violation: {0}")]
    RoleViolation(&'static str),

    /// The peer rejected the session with the given reason code.
    #[error("session rejected by peer: reason {0}")]
    Rejected(u16),

    /// A configured resource ceiling was exceeded.
    #[error("resource limit exceeded: {0}")]
    ResourceLimit(&'static str),

    /// The session has terminated; no further transitions are legal.
    #[error("session is closed (terminal)")]
    Closed,
}

impl SessionError {
    /// Returns `true` for terminal errors that terminate the connection.
    #[must_use]
    pub const fn is_terminal(&self) -> bool {
        match self {
            Self::InvalidConfiguration { .. } | Self::Backpressure => false,
            Self::InvalidStateTransition { .. }
            | Self::ProtocolViolation(_)
            | Self::RoleViolation(_)
            | Self::Rejected(_)
            | Self::ResourceLimit(_)
            | Self::Closed => true,
        }
    }
}
