//! Wire protocol error types.

use std::fmt;

/// Wire-layer protocol framing, decoding, and encoding errors.
///
/// Error messages are category-only and never leak sensitive payload data,
/// key material, or network metadata.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WireError {
    /// The input buffer was truncated before all declared bytes could be read.
    Truncated,
    /// An outer record length was invalid (e.g. 0, < 20 for transport, or mismatched record length).
    InvalidOuterLength,
    /// The frame type code is invalid (0x00, 0xFF, or unassigned core frame).
    InvalidFrameType,
    /// An unknown critical extension frame type (0x80..=0xBF) was encountered.
    UnknownCriticalFrame,
    /// A reserved frame type (0xC0..=0xFE) was encountered.
    ReservedFrameType,
    /// Undefined flag bits on a core frame were non-zero.
    InvalidFlags,
    /// The declared frame body length is invalid or violates frame bounds.
    InvalidBodyLength,
    /// The frame body length did not match the required fixed length for the frame type.
    InvalidFixedBodyLength,
    /// An identifier (`StreamId`, `ContextId`, `DnsRequestId`) was zero or invalid.
    InvalidIdentifier,
    /// An endpoint structure was malformed (unknown ATYP, truncated address, etc.).
    InvalidEndpoint,
    /// A domain name violated canonical wire format rules.
    InvalidDomain,
    /// A port number was zero.
    InvalidPort,
    /// An invalid combination of status code and associated fields was encountered.
    InvalidStatusCombination,
    /// A payload exceeded the maximum permitted protocol limit.
    OversizedPayload,
    /// Attempted to encode an unsupported frame variant (e.g. `UnknownOptional` or unassigned code).
    UnsupportedEncode,
    /// Arithmetic overflow occurred while calculating lengths or offsets.
    ArithmeticOverflow,
}

impl fmt::Display for WireError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Truncated => write!(f, "truncated wire input"),
            Self::InvalidOuterLength => write!(f, "invalid outer length"),
            Self::InvalidFrameType => write!(f, "invalid frame type"),
            Self::UnknownCriticalFrame => write!(f, "unknown critical frame type"),
            Self::ReservedFrameType => write!(f, "reserved frame type"),
            Self::InvalidFlags => write!(f, "invalid flags for frame type"),
            Self::InvalidBodyLength => write!(f, "invalid frame body length"),
            Self::InvalidFixedBodyLength => write!(f, "invalid fixed body length"),
            Self::InvalidIdentifier => write!(f, "invalid protocol identifier"),
            Self::InvalidEndpoint => write!(f, "invalid endpoint encoding"),
            Self::InvalidDomain => write!(f, "invalid domain name"),
            Self::InvalidPort => write!(f, "invalid port number"),
            Self::InvalidStatusCombination => write!(f, "invalid status combination"),
            Self::OversizedPayload => write!(f, "oversized payload"),
            Self::UnsupportedEncode => write!(f, "unsupported encode"),
            Self::ArithmeticOverflow => write!(f, "arithmetic overflow"),
        }
    }
}

impl std::error::Error for WireError {}
