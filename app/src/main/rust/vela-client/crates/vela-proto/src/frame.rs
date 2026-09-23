//! Strongly typed Vela frames and validated constructor helpers.

use std::fmt;

use crate::codes::{
    DatagramCloseReason, GoAwayReason, OpenResultStatus, SessionRejectReason, StreamResetCode,
};
use crate::constants::{MAX_DATAGRAM_PAYLOAD, MAX_DNS_MESSAGE};
use crate::endpoint::Endpoint;
use crate::ids::{ContextId, DnsRequestId, StreamId};

/// Maximum payload length for a `STREAM_DATA` frame body.
///
/// Max Vela plaintext (65,519) - Frame envelope header (4) - `StreamId` (4) = 65,511.
pub const MAX_STREAM_DATA_PAYLOAD: usize = 65_511;

/// Assigned core frame types.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum FrameType {
    SessionAccept = 0x01,
    SessionReject = 0x02,
    Ping = 0x03,
    Pong = 0x04,
    GoAway = 0x05,
    KeyUpdate = 0x06,
    ConnectionMaxData = 0x08,
    StreamOpen = 0x10,
    StreamOpenResult = 0x11,
    StreamData = 0x12,
    StreamFin = 0x13,
    StreamReset = 0x14,
    StreamMaxData = 0x15,
    DatagramOpen = 0x20,
    DatagramOpenResult = 0x21,
    DatagramData = 0x22,
    DatagramClose = 0x23,
    DnsQuery = 0x30,
    DnsResponse = 0x31,
    DnsCancel = 0x32,
}

impl FrameType {
    /// Returns the raw `u8` value of this frame type.
    #[must_use]
    pub const fn as_u8(self) -> u8 {
        self as u8
    }

    /// Converts a raw `u8` into an assigned core [`FrameType`], if known.
    #[must_use]
    pub const fn from_u8(value: u8) -> Option<Self> {
        match value {
            0x01 => Some(Self::SessionAccept),
            0x02 => Some(Self::SessionReject),
            0x03 => Some(Self::Ping),
            0x04 => Some(Self::Pong),
            0x05 => Some(Self::GoAway),
            0x06 => Some(Self::KeyUpdate),
            0x08 => Some(Self::ConnectionMaxData),
            0x10 => Some(Self::StreamOpen),
            0x11 => Some(Self::StreamOpenResult),
            0x12 => Some(Self::StreamData),
            0x13 => Some(Self::StreamFin),
            0x14 => Some(Self::StreamReset),
            0x15 => Some(Self::StreamMaxData),
            0x20 => Some(Self::DatagramOpen),
            0x21 => Some(Self::DatagramOpenResult),
            0x22 => Some(Self::DatagramData),
            0x23 => Some(Self::DatagramClose),
            0x30 => Some(Self::DnsQuery),
            0x31 => Some(Self::DnsResponse),
            0x32 => Some(Self::DnsCancel),
            _ => None,
        }
    }
}

/// Errors returned when validating semantic invariants during frame construction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SemanticError {
    /// An open result frame with non-success status had a non-zero `initial_max_data`.
    InvalidStatusCombination,
    /// A frame payload was empty when non-empty data is required (e.g. `STREAM_DATA`).
    EmptyPayload,
    /// A frame payload exceeded the maximum allowable length.
    OversizedPayload,
}

impl fmt::Display for SemanticError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidStatusCombination => {
                write!(
                    f,
                    "non-success open result status requires initial_max_data == 0"
                )
            }
            Self::EmptyPayload => write!(f, "frame payload must not be empty"),
            Self::OversizedPayload => write!(f, "frame payload exceeds maximum permitted length"),
        }
    }
}

impl std::error::Error for SemanticError {}

/// Closed enum representing all assigned core Vela frames and unknown optional extensions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    SessionAccept,
    SessionReject {
        reason: SessionRejectReason,
    },
    Ping {
        opaque: u64,
    },
    Pong {
        opaque: u64,
    },
    GoAway {
        last_stream_id: u32,
        reason: GoAwayReason,
    },
    KeyUpdate,
    ConnectionMaxData {
        maximum_data: u64,
    },
    StreamOpen {
        stream_id: StreamId,
        endpoint: Endpoint,
        initial_max_data: u64,
    },
    StreamOpenResult {
        stream_id: StreamId,
        status: OpenResultStatus,
        initial_max_data: u64,
    },
    StreamData {
        stream_id: StreamId,
        data: Vec<u8>,
    },
    StreamFin {
        stream_id: StreamId,
    },
    StreamReset {
        stream_id: StreamId,
        error: StreamResetCode,
    },
    StreamMaxData {
        stream_id: StreamId,
        maximum_data: u64,
    },
    DatagramOpen {
        context_id: ContextId,
        endpoint: Endpoint,
    },
    DatagramOpenResult {
        context_id: ContextId,
        status: OpenResultStatus,
    },
    DatagramData {
        context_id: ContextId,
        payload: Vec<u8>,
    },
    DatagramClose {
        context_id: ContextId,
        reason: DatagramCloseReason,
    },
    DnsQuery {
        request_id: DnsRequestId,
        message: Vec<u8>,
    },
    DnsResponse {
        request_id: DnsRequestId,
        message: Vec<u8>,
    },
    DnsCancel {
        request_id: DnsRequestId,
    },
    UnknownOptional {
        frame_type: u8,
    },
}

impl Frame {
    /// Returns the raw `u8` wire frame type code for this frame.
    #[must_use]
    pub const fn frame_type(&self) -> u8 {
        match self {
            Self::SessionAccept => FrameType::SessionAccept.as_u8(),
            Self::SessionReject { .. } => FrameType::SessionReject.as_u8(),
            Self::Ping { .. } => FrameType::Ping.as_u8(),
            Self::Pong { .. } => FrameType::Pong.as_u8(),
            Self::GoAway { .. } => FrameType::GoAway.as_u8(),
            Self::KeyUpdate => FrameType::KeyUpdate.as_u8(),
            Self::ConnectionMaxData { .. } => FrameType::ConnectionMaxData.as_u8(),
            Self::StreamOpen { .. } => FrameType::StreamOpen.as_u8(),
            Self::StreamOpenResult { .. } => FrameType::StreamOpenResult.as_u8(),
            Self::StreamData { .. } => FrameType::StreamData.as_u8(),
            Self::StreamFin { .. } => FrameType::StreamFin.as_u8(),
            Self::StreamReset { .. } => FrameType::StreamReset.as_u8(),
            Self::StreamMaxData { .. } => FrameType::StreamMaxData.as_u8(),
            Self::DatagramOpen { .. } => FrameType::DatagramOpen.as_u8(),
            Self::DatagramOpenResult { .. } => FrameType::DatagramOpenResult.as_u8(),
            Self::DatagramData { .. } => FrameType::DatagramData.as_u8(),
            Self::DatagramClose { .. } => FrameType::DatagramClose.as_u8(),
            Self::DnsQuery { .. } => FrameType::DnsQuery.as_u8(),
            Self::DnsResponse { .. } => FrameType::DnsResponse.as_u8(),
            Self::DnsCancel { .. } => FrameType::DnsCancel.as_u8(),
            Self::UnknownOptional { frame_type } => *frame_type,
        }
    }

    /// Constructs a validated `STREAM_OPEN_RESULT` frame.
    ///
    /// # Errors
    ///
    /// Returns [`SemanticError::InvalidStatusCombination`] if `status` is not [`OpenResultStatus::Ok`]
    /// and `initial_max_data != 0`.
    pub fn stream_open_result(
        stream_id: StreamId,
        status: OpenResultStatus,
        initial_max_data: u64,
    ) -> Result<Self, SemanticError> {
        if !status.is_success() && initial_max_data != 0 {
            return Err(SemanticError::InvalidStatusCombination);
        }
        Ok(Self::StreamOpenResult {
            stream_id,
            status,
            initial_max_data,
        })
    }

    /// Constructs a validated `STREAM_DATA` frame.
    ///
    /// # Errors
    ///
    /// Returns [`SemanticError::EmptyPayload`] if `data` is empty, or
    /// [`SemanticError::OversizedPayload`] if `data.len() > 65,511`.
    pub fn stream_data(stream_id: StreamId, data: Vec<u8>) -> Result<Self, SemanticError> {
        if data.is_empty() {
            return Err(SemanticError::EmptyPayload);
        }
        if data.len() > MAX_STREAM_DATA_PAYLOAD {
            return Err(SemanticError::OversizedPayload);
        }
        Ok(Self::StreamData { stream_id, data })
    }

    /// Constructs a validated `DATAGRAM_DATA` frame.
    ///
    /// # Errors
    ///
    /// Returns [`SemanticError::OversizedPayload`] if `payload.len() > 65,507`.
    pub fn datagram_data(context_id: ContextId, payload: Vec<u8>) -> Result<Self, SemanticError> {
        if payload.len() > MAX_DATAGRAM_PAYLOAD {
            return Err(SemanticError::OversizedPayload);
        }
        Ok(Self::DatagramData {
            context_id,
            payload,
        })
    }

    /// Constructs a validated `DNS_QUERY` frame.
    ///
    /// # Errors
    ///
    /// Returns [`SemanticError::OversizedPayload`] if `message.len() > 65,507`.
    pub fn dns_query(request_id: DnsRequestId, message: Vec<u8>) -> Result<Self, SemanticError> {
        if message.len() > MAX_DNS_MESSAGE {
            return Err(SemanticError::OversizedPayload);
        }
        Ok(Self::DnsQuery {
            request_id,
            message,
        })
    }

    /// Constructs a validated `DNS_RESPONSE` frame.
    ///
    /// # Errors
    ///
    /// Returns [`SemanticError::OversizedPayload`] if `message.len() > 65,507`.
    pub fn dns_response(request_id: DnsRequestId, message: Vec<u8>) -> Result<Self, SemanticError> {
        if message.len() > MAX_DNS_MESSAGE {
            return Err(SemanticError::OversizedPayload);
        }
        Ok(Self::DnsResponse {
            request_id,
            message,
        })
    }
}
