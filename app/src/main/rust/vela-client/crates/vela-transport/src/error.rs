//! Partitioned error taxonomy for the Vela transport layer.
//!
//! Every error other than a local configuration mistake is terminal: the
//! connection is closed and there is **no direct fallback** (spec §16). The
//! transport never retries a failed cryptographic or protocol operation.

use vela_crypto::CryptoError;
use vela_session::SessionError;
use vela_wire::WireError;

/// Errors produced by the Vela transport layer.
#[derive(Debug, thiserror::Error)]
pub enum TransportError {
    /// A transport configuration value was invalid (recoverable; no I/O).
    #[error("invalid transport configuration: {field}")]
    InvalidConfiguration {
        /// Name of the offending configuration field.
        field: &'static str,
    },

    /// A byte-stream I/O operation failed. Timeouts surface here as
    /// `io::ErrorKind::TimedOut` and are terminal (fail-closed).
    #[error("transport I/O failure: {0}")]
    Io(#[from] std::io::Error),

    /// The peer closed the stream before a complete record arrived.
    #[error("peer closed the connection mid-record")]
    PeerClosed,

    /// The cryptographic engine failed (authentication, nonce, poison).
    #[error("transport cryptographic failure: {0}")]
    Crypto(#[from] CryptoError),

    /// The session layer reported a protocol violation.
    #[error("transport session failure: {0}")]
    Session(#[from] SessionError),

    /// The wire codec rejected a record or frame.
    #[error("transport wire failure: {0}")]
    Wire(#[from] WireError),

    /// The connection has already terminated; no further operations are legal.
    #[error("transport connection is closed (terminal)")]
    Closed,

    /// The server rejected the authenticated client before session
    /// establishment (spec §5.2).
    #[error("client authorization failed")]
    AuthorizationFailed,

    /// An exact first-flight (M1) replay was suppressed without writing M2.
    #[error("handshake first-flight replay suppressed")]
    HandshakeReplay,

    /// A connection-pool acquire waited at capacity until `acquire_timeout`.
    /// Recoverable: no session was mutated.
    #[error("connection pool acquire timed out")]
    AcquireTimeout,
}

impl TransportError {
    /// Returns `true` for terminal errors that close the connection.
    #[must_use]
    pub const fn is_terminal(&self) -> bool {
        match self {
            Self::InvalidConfiguration { .. }
            | Self::AcquireTimeout
            | Self::Session(SessionError::Backpressure) => false,
            Self::Io(_)
            | Self::PeerClosed
            | Self::Crypto(_)
            | Self::Session(_)
            | Self::Wire(_)
            | Self::Closed
            | Self::AuthorizationFailed
            | Self::HandshakeReplay => true,
        }
    }
}
