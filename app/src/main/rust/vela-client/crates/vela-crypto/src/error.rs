//! Partitioned error taxonomy for the Vela cryptographic engine.
//!
//! Errors are strictly partitioned into:
//! - **Recoverable local errors**: parameter checks performed before any
//!   cryptographic state mutation. The caller may retry with corrected input.
//! - **Terminal crypto / peer errors**: irreversible failures that permanently
//!   invalidate the cryptographic session (fail-closed, no fallback).

/// Errors produced by the Vela cryptographic engine.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CryptoError {
    // -------------------------------------------------------------------
    // RECOVERABLE LOCAL ERRORS (parameter checks before state mutation)
    // -------------------------------------------------------------------
    /// A key-like input did not have the required byte length.
    #[error("invalid key length: expected {expected}, got {actual}")]
    InvalidKeyLength {
        /// Required byte length.
        expected: usize,
        /// Actual byte length provided.
        actual: usize,
    },

    /// An output buffer was too small for the operation result.
    #[error("output buffer too small: required {required}, provided {provided}")]
    BufferTooSmall {
        /// Required output buffer length in bytes.
        required: usize,
        /// Provided output buffer length in bytes.
        provided: usize,
    },

    /// A transport plaintext violated the frozen length bounds (4..=65519).
    #[error("plaintext length out of bounds: {0}")]
    PlaintextOutOfBounds(usize),

    /// A transport ciphertext violated the frozen length bounds (20..=65535).
    #[error("ciphertext length out of bounds: {0}")]
    CiphertextOutOfBounds(usize),

    // -------------------------------------------------------------------
    // TERMINAL CRYPTO / PEER ERRORS (irreversible session invalidation)
    // -------------------------------------------------------------------
    /// AEAD authentication failure / invalid authentication tag.
    #[error("AEAD authentication failure / invalid tag")]
    AuthenticationFailed,

    /// A handshake flight did not have its exact frozen byte length.
    #[error("invalid handshake flight length: expected {expected}, got {actual}")]
    FlightLengthMismatch {
        /// Frozen flight length in bytes.
        expected: usize,
        /// Actual flight length in bytes.
        actual: usize,
    },

    /// A handshake payload did not match the frozen handshake payload.
    #[error("handshake message payload mismatch")]
    PayloadMismatch,

    /// A handshake method was invoked out of its required sequence.
    #[error("invalid handshake state progression")]
    InvalidStateProgression,

    /// The transport nonce reached exhaustion; fail-closed, no continuation.
    #[error("transport nonce exhausted (terminal failure)")]
    NonceExhausted,

    /// The session has been terminally poisoned by an earlier failure.
    #[error("cryptographic session is terminally poisoned")]
    SessionPoisoned,

    /// The underlying cryptographic engine reported an unrecoverable failure.
    #[error("underlying cryptographic engine failure")]
    EngineFailure,
}

impl CryptoError {
    /// Returns `true` for terminal errors that permanently invalidate the session.
    #[must_use]
    pub const fn is_terminal(&self) -> bool {
        match self {
            Self::InvalidKeyLength { .. }
            | Self::BufferTooSmall { .. }
            | Self::PlaintextOutOfBounds(_)
            | Self::CiphertextOutOfBounds(_) => false,
            Self::AuthenticationFailed
            | Self::FlightLengthMismatch { .. }
            | Self::PayloadMismatch
            | Self::InvalidStateProgression
            | Self::NonceExhausted
            | Self::SessionPoisoned
            | Self::EngineFailure => true,
        }
    }
}
