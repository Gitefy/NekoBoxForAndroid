//! Canonical Vela frame decoding and encoding.

use vela_proto::{
    ContextId, DatagramCloseReason, DnsRequestId, Endpoint, Frame, GoAwayReason,
    MAX_DATAGRAM_PAYLOAD, MAX_DNS_MESSAGE, MAX_DOMAIN_LEN, MAX_STREAM_DATA_PAYLOAD,
    MAX_VELA_PLAINTEXT, MIN_VELA_PLAINTEXT, OpenResultStatus, SessionRejectReason, StreamId,
    StreamResetCode,
};

use crate::endpoint::{decode_endpoint, encode_endpoint};
use crate::error::WireError;

/// Result of decoding an authenticated Vela plaintext record.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedFrame {
    /// The decoded semantic frame.
    pub frame: Frame,
    /// Number of authenticated suffix padding bytes following the frame body.
    pub padding_len: usize,
}

/// Decodes an authenticated Vela plaintext record into a [`DecodedFrame`].
///
/// Plaintext must be between 4 and 65,519 bytes inclusive.
///
/// # Errors
///
/// Returns:
/// - [`WireError::Truncated`] if `plaintext.len() < 4`.
/// - [`WireError::OversizedPayload`] if `plaintext.len() > 65,519` or frame payload exceeds limits.
/// - [`WireError::InvalidFrameType`] for type 0x00, 0xFF, or unassigned core frame types.
/// - [`WireError::InvalidFlags`] if undefined flag bits are non-zero on a core frame.
/// - [`WireError::InvalidBodyLength`] if the declared body length exceeds available bytes.
/// - [`WireError::InvalidFixedBodyLength`] if a fixed-length frame does not match its required length.
/// - [`WireError::InvalidIdentifier`] if a stream, context, or DNS request ID is zero.
/// - [`WireError::InvalidStatusCombination`] if a failed open result specifies a non-zero initial max data.
/// - [`WireError::UnknownCriticalFrame`] for unknown critical frames (0x80..=0xBF).
/// - [`WireError::ReservedFrameType`] for reserved frame types (0xC0..=0xFE).
pub fn decode_frame(plaintext: &[u8]) -> Result<DecodedFrame, WireError> {
    // 1. Mandatory total plaintext bound check before parsing or allocating
    if plaintext.len() < MIN_VELA_PLAINTEXT {
        return Err(WireError::Truncated);
    }
    if plaintext.len() > MAX_VELA_PLAINTEXT {
        return Err(WireError::OversizedPayload);
    }

    // 2. Read envelope header
    let frame_type = plaintext[0];
    let flags = plaintext[1];
    let body_len = u16::from_be_bytes([plaintext[2], plaintext[3]]) as usize;

    // 3. Body boundary check
    let body_end = 4_usize
        .checked_add(body_len)
        .ok_or(WireError::ArithmeticOverflow)?;
    if body_end > plaintext.len() {
        return Err(WireError::InvalidBodyLength);
    }

    // 4. Validate flags and type dispatch
    let frame = match frame_type {
        0x00 | 0xff => return Err(WireError::InvalidFrameType),
        0x01..=0x3f => {
            // Undefined flag bits on core frames MUST be zero
            if flags != 0 {
                return Err(WireError::InvalidFlags);
            }
            let body = &plaintext[4..body_end];
            decode_core_frame(frame_type, body_len, body)?
        }
        0x40..=0x7f => {
            // Unknown optional extension: body ignored, flags not validated
            Frame::UnknownOptional { frame_type }
        }
        0x80..=0xbf => return Err(WireError::UnknownCriticalFrame),
        0xc0..=0xfe => return Err(WireError::ReservedFrameType),
    };

    let padding_len = plaintext.len() - body_end;
    Ok(DecodedFrame { frame, padding_len })
}

fn decode_core_frame(frame_type: u8, body_len: usize, body: &[u8]) -> Result<Frame, WireError> {
    match frame_type {
        0x01..=0x06 | 0x08 => decode_session_control_frame(frame_type, body_len, body),
        0x10..=0x15 => decode_stream_frame(frame_type, body_len, body),
        0x20..=0x23 => decode_datagram_frame(frame_type, body_len, body),
        0x30..=0x32 => decode_dns_frame(frame_type, body_len, body),
        _ => Err(WireError::InvalidFrameType),
    }
}

fn decode_session_control_frame(
    frame_type: u8,
    body_len: usize,
    body: &[u8],
) -> Result<Frame, WireError> {
    match frame_type {
        0x01 => {
            if body_len != 0 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            Ok(Frame::SessionAccept)
        }
        0x02 => {
            if body_len != 2 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let code = u16::from_be_bytes([body[0], body[1]]);
            Ok(Frame::SessionReject {
                reason: SessionRejectReason::from_u16(code),
            })
        }
        0x03 => {
            if body_len != 8 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let opaque = u64::from_be_bytes([
                body[0], body[1], body[2], body[3], body[4], body[5], body[6], body[7],
            ]);
            Ok(Frame::Ping { opaque })
        }
        0x04 => {
            if body_len != 8 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let opaque = u64::from_be_bytes([
                body[0], body[1], body[2], body[3], body[4], body[5], body[6], body[7],
            ]);
            Ok(Frame::Pong { opaque })
        }
        0x05 => {
            if body_len != 6 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let last_stream_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let code = u16::from_be_bytes([body[4], body[5]]);
            Ok(Frame::GoAway {
                last_stream_id,
                reason: GoAwayReason::from_u16(code),
            })
        }
        0x06 => {
            if body_len != 0 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            Ok(Frame::KeyUpdate)
        }
        0x08 => {
            if body_len != 8 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let maximum_data = u64::from_be_bytes([
                body[0], body[1], body[2], body[3], body[4], body[5], body[6], body[7],
            ]);
            Ok(Frame::ConnectionMaxData { maximum_data })
        }
        _ => Err(WireError::InvalidFrameType),
    }
}

fn decode_stream_frame(frame_type: u8, body_len: usize, body: &[u8]) -> Result<Frame, WireError> {
    match frame_type {
        0x10 => {
            // STREAM_OPEN: StreamID (4) + Endpoint + InitialMaxData (8)
            // Fixed prefix (4) + minimum endpoint (5) + fixed suffix (8) = 17
            if body_len < 17 {
                return Err(WireError::InvalidBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let stream_id = StreamId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let (endpoint, consumed) = decode_endpoint(&body[4..body_len])?;
            if 4 + consumed + 8 != body_len {
                return Err(WireError::InvalidBodyLength);
            }

            let imd_offset = 4 + consumed;
            let initial_max_data = u64::from_be_bytes([
                body[imd_offset],
                body[imd_offset + 1],
                body[imd_offset + 2],
                body[imd_offset + 3],
                body[imd_offset + 4],
                body[imd_offset + 5],
                body[imd_offset + 6],
                body[imd_offset + 7],
            ]);

            Ok(Frame::StreamOpen {
                stream_id,
                endpoint,
                initial_max_data,
            })
        }
        0x11 => {
            if body_len != 14 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let stream_id = StreamId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let code = u16::from_be_bytes([body[4], body[5]]);
            let status = OpenResultStatus::from_u16(code);

            let initial_max_data = u64::from_be_bytes([
                body[6], body[7], body[8], body[9], body[10], body[11], body[12], body[13],
            ]);

            if !status.is_success() && initial_max_data != 0 {
                return Err(WireError::InvalidStatusCombination);
            }

            Ok(Frame::StreamOpenResult {
                stream_id,
                status,
                initial_max_data,
            })
        }
        0x12 => {
            // STREAM_DATA: StreamID (4) + Data (>= 1 byte)
            if body_len < 5 {
                return Err(WireError::InvalidBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let stream_id = StreamId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let data = body[4..].to_vec();
            if data.len() > MAX_STREAM_DATA_PAYLOAD {
                return Err(WireError::OversizedPayload);
            }

            Ok(Frame::StreamData { stream_id, data })
        }
        0x13 => {
            if body_len != 4 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let stream_id = StreamId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;
            Ok(Frame::StreamFin { stream_id })
        }
        0x14 => {
            if body_len != 6 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let stream_id = StreamId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let code = u16::from_be_bytes([body[4], body[5]]);
            let error = StreamResetCode::from_u16(code);

            Ok(Frame::StreamReset { stream_id, error })
        }
        0x15 => {
            if body_len != 12 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let stream_id = StreamId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let maximum_data = u64::from_be_bytes([
                body[4], body[5], body[6], body[7], body[8], body[9], body[10], body[11],
            ]);

            Ok(Frame::StreamMaxData {
                stream_id,
                maximum_data,
            })
        }
        _ => Err(WireError::InvalidFrameType),
    }
}

fn decode_datagram_frame(frame_type: u8, body_len: usize, body: &[u8]) -> Result<Frame, WireError> {
    match frame_type {
        0x20 => {
            // DATAGRAM_OPEN: ContextID (4) + Endpoint
            // Fixed prefix (4) + minimum endpoint (5) = 9
            if body_len < 9 {
                return Err(WireError::InvalidBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let context_id = ContextId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let (endpoint, consumed) = decode_endpoint(&body[4..body_len])?;
            if 4 + consumed != body_len {
                return Err(WireError::InvalidBodyLength);
            }

            Ok(Frame::DatagramOpen {
                context_id,
                endpoint,
            })
        }
        0x21 => {
            if body_len != 6 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let context_id = ContextId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let code = u16::from_be_bytes([body[4], body[5]]);
            let status = OpenResultStatus::from_u16(code);

            Ok(Frame::DatagramOpenResult { context_id, status })
        }
        0x22 => {
            // DATAGRAM_DATA: ContextID (4) + Payload (0..=65507 bytes)
            if body_len < 4 {
                return Err(WireError::InvalidBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let context_id = ContextId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let payload = body[4..].to_vec();
            if payload.len() > MAX_DATAGRAM_PAYLOAD {
                return Err(WireError::OversizedPayload);
            }

            Ok(Frame::DatagramData {
                context_id,
                payload,
            })
        }
        0x23 => {
            if body_len != 6 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let context_id = ContextId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let code = u16::from_be_bytes([body[4], body[5]]);
            let reason = DatagramCloseReason::from_u16(code);

            Ok(Frame::DatagramClose { context_id, reason })
        }
        _ => Err(WireError::InvalidFrameType),
    }
}

fn decode_dns_frame(frame_type: u8, body_len: usize, body: &[u8]) -> Result<Frame, WireError> {
    match frame_type {
        0x30 => {
            // DNS_QUERY: DnsRequestID (4) + Message (0..=65507 bytes)
            if body_len < 4 {
                return Err(WireError::InvalidBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let request_id = DnsRequestId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let message = body[4..].to_vec();
            if message.len() > MAX_DNS_MESSAGE {
                return Err(WireError::OversizedPayload);
            }

            Ok(Frame::DnsQuery {
                request_id,
                message,
            })
        }
        0x31 => {
            // DNS_RESPONSE: DnsRequestID (4) + Message (0..=65507 bytes)
            if body_len < 4 {
                return Err(WireError::InvalidBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let request_id = DnsRequestId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            let message = body[4..].to_vec();
            if message.len() > MAX_DNS_MESSAGE {
                return Err(WireError::OversizedPayload);
            }

            Ok(Frame::DnsResponse {
                request_id,
                message,
            })
        }
        0x32 => {
            if body_len != 4 {
                return Err(WireError::InvalidFixedBodyLength);
            }
            let raw_id = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
            let request_id = DnsRequestId::new(raw_id).ok_or(WireError::InvalidIdentifier)?;

            Ok(Frame::DnsCancel { request_id })
        }
        _ => Err(WireError::InvalidFrameType),
    }
}

/// Calculates the total plaintext length from body length and padding length, ensuring bounds.
///
/// Plaintext envelope header is 4 bytes. Total length must be in `4..=65519`.
///
/// # Errors
///
/// Returns:
/// - [`WireError::ArithmeticOverflow`] on integer overflow.
/// - [`WireError::OversizedPayload`] if total plaintext exceeds 65,519 bytes.
pub(crate) fn checked_plaintext_len(
    body_len: usize,
    padding_len: usize,
) -> Result<usize, WireError> {
    let total = 4_usize
        .checked_add(body_len)
        .and_then(|n| n.checked_add(padding_len))
        .ok_or(WireError::ArithmeticOverflow)?;
    if total > MAX_VELA_PLAINTEXT {
        return Err(WireError::OversizedPayload);
    }
    Ok(total)
}

fn endpoint_wire_len(endpoint: &Endpoint) -> Result<usize, WireError> {
    match endpoint {
        Endpoint::Ipv4 { .. } => Ok(7),
        Endpoint::Ipv6 { .. } => Ok(19),
        Endpoint::Domain { name, .. } => {
            let s = name.as_str();
            if s.is_empty() || s.len() > MAX_DOMAIN_LEN || s.len() > 255 {
                return Err(WireError::InvalidDomain);
            }
            Ok(4 + s.len())
        }
    }
}

fn validate_and_compute_body_len(frame: &Frame) -> Result<usize, WireError> {
    match frame {
        Frame::UnknownOptional { .. } => Err(WireError::UnsupportedEncode),
        Frame::SessionAccept
        | Frame::SessionReject { .. }
        | Frame::Ping { .. }
        | Frame::Pong { .. }
        | Frame::GoAway { .. }
        | Frame::KeyUpdate
        | Frame::ConnectionMaxData { .. } => validate_session_control_body_len(frame),
        Frame::StreamOpen { .. }
        | Frame::StreamOpenResult { .. }
        | Frame::StreamData { .. }
        | Frame::StreamFin { .. }
        | Frame::StreamReset { .. }
        | Frame::StreamMaxData { .. } => validate_stream_body_len(frame),
        Frame::DatagramOpen { .. }
        | Frame::DatagramOpenResult { .. }
        | Frame::DatagramData { .. }
        | Frame::DatagramClose { .. } => validate_datagram_body_len(frame),
        Frame::DnsQuery { .. } | Frame::DnsResponse { .. } | Frame::DnsCancel { .. } => {
            validate_dns_body_len(frame)
        }
    }
}

fn validate_session_control_body_len(frame: &Frame) -> Result<usize, WireError> {
    match frame {
        Frame::SessionAccept | Frame::KeyUpdate => Ok(0),
        Frame::SessionReject { reason } => {
            if !reason.is_assigned() {
                return Err(WireError::UnsupportedEncode);
            }
            Ok(2)
        }
        Frame::Ping { .. } | Frame::Pong { .. } | Frame::ConnectionMaxData { .. } => Ok(8),
        Frame::GoAway { reason, .. } => {
            if !reason.is_assigned() {
                return Err(WireError::UnsupportedEncode);
            }
            Ok(6)
        }
        _ => Err(WireError::InvalidFrameType),
    }
}

fn validate_stream_body_len(frame: &Frame) -> Result<usize, WireError> {
    match frame {
        Frame::StreamOpen { endpoint, .. } => {
            let ep_len = endpoint_wire_len(endpoint)?;
            Ok(4 + ep_len + 8)
        }
        Frame::StreamOpenResult {
            status,
            initial_max_data,
            ..
        } => {
            if !status.is_assigned() {
                return Err(WireError::UnsupportedEncode);
            }
            if !status.is_success() && *initial_max_data != 0 {
                return Err(WireError::InvalidStatusCombination);
            }
            Ok(14)
        }
        Frame::StreamData { data, .. } => {
            if data.is_empty() {
                return Err(WireError::InvalidBodyLength);
            }
            if data.len() > MAX_STREAM_DATA_PAYLOAD {
                return Err(WireError::OversizedPayload);
            }
            Ok(4 + data.len())
        }
        Frame::StreamFin { .. } => Ok(4),
        Frame::StreamReset { error, .. } => {
            if !error.is_assigned() {
                return Err(WireError::UnsupportedEncode);
            }
            Ok(6)
        }
        Frame::StreamMaxData { .. } => Ok(12),
        _ => Err(WireError::InvalidFrameType),
    }
}

fn validate_datagram_body_len(frame: &Frame) -> Result<usize, WireError> {
    match frame {
        Frame::DatagramOpen { endpoint, .. } => {
            let ep_len = endpoint_wire_len(endpoint)?;
            Ok(4 + ep_len)
        }
        Frame::DatagramOpenResult { status, .. } => {
            if !status.is_assigned() {
                return Err(WireError::UnsupportedEncode);
            }
            Ok(6)
        }
        Frame::DatagramData { payload, .. } => {
            if payload.len() > MAX_DATAGRAM_PAYLOAD {
                return Err(WireError::OversizedPayload);
            }
            Ok(4 + payload.len())
        }
        Frame::DatagramClose { reason, .. } => {
            if !reason.is_assigned() {
                return Err(WireError::UnsupportedEncode);
            }
            Ok(6)
        }
        _ => Err(WireError::InvalidFrameType),
    }
}

fn validate_dns_body_len(frame: &Frame) -> Result<usize, WireError> {
    match frame {
        Frame::DnsQuery { message, .. } | Frame::DnsResponse { message, .. } => {
            if message.len() > MAX_DNS_MESSAGE {
                return Err(WireError::OversizedPayload);
            }
            Ok(4 + message.len())
        }
        Frame::DnsCancel { .. } => Ok(4),
        _ => Err(WireError::InvalidFrameType),
    }
}

fn encode_session_body(frame: &Frame, out: &mut Vec<u8>) {
    match frame {
        Frame::SessionReject { reason } => {
            out.extend_from_slice(&reason.as_u16().to_be_bytes());
        }
        Frame::Ping { opaque } | Frame::Pong { opaque } => {
            out.extend_from_slice(&opaque.to_be_bytes());
        }
        Frame::GoAway {
            last_stream_id,
            reason,
        } => {
            out.extend_from_slice(&last_stream_id.to_be_bytes());
            out.extend_from_slice(&reason.as_u16().to_be_bytes());
        }
        Frame::ConnectionMaxData { maximum_data } => {
            out.extend_from_slice(&maximum_data.to_be_bytes());
        }
        _ => {}
    }
}

fn encode_stream_body(frame: &Frame, out: &mut Vec<u8>) -> Result<(), WireError> {
    match frame {
        Frame::StreamOpen {
            stream_id,
            endpoint,
            initial_max_data,
        } => {
            out.extend_from_slice(&stream_id.get().to_be_bytes());
            encode_endpoint(endpoint, out)?;
            out.extend_from_slice(&initial_max_data.to_be_bytes());
        }
        Frame::StreamOpenResult {
            stream_id,
            status,
            initial_max_data,
        } => {
            out.extend_from_slice(&stream_id.get().to_be_bytes());
            out.extend_from_slice(&status.as_u16().to_be_bytes());
            out.extend_from_slice(&initial_max_data.to_be_bytes());
        }
        Frame::StreamData { stream_id, data } => {
            out.extend_from_slice(&stream_id.get().to_be_bytes());
            out.extend_from_slice(data);
        }
        Frame::StreamFin { stream_id } => {
            out.extend_from_slice(&stream_id.get().to_be_bytes());
        }
        Frame::StreamReset { stream_id, error } => {
            out.extend_from_slice(&stream_id.get().to_be_bytes());
            out.extend_from_slice(&error.as_u16().to_be_bytes());
        }
        Frame::StreamMaxData {
            stream_id,
            maximum_data,
        } => {
            out.extend_from_slice(&stream_id.get().to_be_bytes());
            out.extend_from_slice(&maximum_data.to_be_bytes());
        }
        _ => return Err(WireError::InvalidFrameType),
    }
    Ok(())
}

fn encode_datagram_body(frame: &Frame, out: &mut Vec<u8>) -> Result<(), WireError> {
    match frame {
        Frame::DatagramOpen {
            context_id,
            endpoint,
        } => {
            out.extend_from_slice(&context_id.get().to_be_bytes());
            encode_endpoint(endpoint, out)?;
        }
        Frame::DatagramOpenResult { context_id, status } => {
            out.extend_from_slice(&context_id.get().to_be_bytes());
            out.extend_from_slice(&status.as_u16().to_be_bytes());
        }
        Frame::DatagramData {
            context_id,
            payload,
        } => {
            out.extend_from_slice(&context_id.get().to_be_bytes());
            out.extend_from_slice(payload);
        }
        Frame::DatagramClose { context_id, reason } => {
            out.extend_from_slice(&context_id.get().to_be_bytes());
            out.extend_from_slice(&reason.as_u16().to_be_bytes());
        }
        _ => return Err(WireError::InvalidFrameType),
    }
    Ok(())
}

fn encode_dns_body(frame: &Frame, out: &mut Vec<u8>) {
    match frame {
        Frame::DnsQuery {
            request_id,
            message,
        }
        | Frame::DnsResponse {
            request_id,
            message,
        } => {
            out.extend_from_slice(&request_id.get().to_be_bytes());
            out.extend_from_slice(message);
        }
        Frame::DnsCancel { request_id } => {
            out.extend_from_slice(&request_id.get().to_be_bytes());
        }
        _ => {}
    }
}

/// Encodes a semantic [`Frame`] with optional authenticated suffix padding into canonical wire bytes.
///
/// Plaintext total length must not exceed 65,519 bytes.
///
/// # Errors
///
/// Returns:
/// - [`WireError::UnsupportedEncode`] for decode-only [`Frame::UnknownOptional`] or unassigned code registry values.
/// - [`WireError::InvalidStatusCombination`] if a non-success status specifies non-zero initial max data.
/// - [`WireError::InvalidBodyLength`] if `STREAM_DATA` has empty payload or body length exceeds `u16::MAX`.
/// - [`WireError::OversizedPayload`] if payload or total plaintext length exceeds maximum limits.
/// - [`WireError::ArithmeticOverflow`] if plaintext length calculations overflow `usize`.
pub fn encode_frame(frame: &Frame, padding: &[u8]) -> Result<Vec<u8>, WireError> {
    let body_len = validate_and_compute_body_len(frame)?;
    let body_len_u16 = u16::try_from(body_len).map_err(|_| WireError::InvalidBodyLength)?;

    let total_len = checked_plaintext_len(body_len, padding.len())?;

    let mut out = Vec::with_capacity(total_len);
    out.push(frame.frame_type());
    out.push(0x00);
    out.extend_from_slice(&body_len_u16.to_be_bytes());

    match frame {
        Frame::SessionAccept
        | Frame::SessionReject { .. }
        | Frame::Ping { .. }
        | Frame::Pong { .. }
        | Frame::GoAway { .. }
        | Frame::KeyUpdate
        | Frame::ConnectionMaxData { .. } => encode_session_body(frame, &mut out),
        Frame::StreamOpen { .. }
        | Frame::StreamOpenResult { .. }
        | Frame::StreamData { .. }
        | Frame::StreamFin { .. }
        | Frame::StreamReset { .. }
        | Frame::StreamMaxData { .. } => {
            encode_stream_body(frame, &mut out)?;
        }
        Frame::DatagramOpen { .. }
        | Frame::DatagramOpenResult { .. }
        | Frame::DatagramData { .. }
        | Frame::DatagramClose { .. } => {
            encode_datagram_body(frame, &mut out)?;
        }
        Frame::DnsQuery { .. } | Frame::DnsResponse { .. } | Frame::DnsCancel { .. } => {
            encode_dns_body(frame, &mut out);
        }
        Frame::UnknownOptional { .. } => return Err(WireError::UnsupportedEncode),
    }

    out.extend_from_slice(padding);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checked_plaintext_len_overflow_and_boundaries() {
        // Three integer overflow inputs return ArithmeticOverflow without memory allocation
        assert_eq!(
            checked_plaintext_len(0, usize::MAX),
            Err(WireError::ArithmeticOverflow)
        );
        assert_eq!(
            checked_plaintext_len(usize::MAX, 0),
            Err(WireError::ArithmeticOverflow)
        );
        assert_eq!(
            checked_plaintext_len(usize::MAX - 3, 0),
            Err(WireError::ArithmeticOverflow)
        );

        // Four length boundary inputs return Ok(65519) or OversizedPayload
        assert_eq!(checked_plaintext_len(0, 65515), Ok(65519));
        assert_eq!(
            checked_plaintext_len(0, 65516),
            Err(WireError::OversizedPayload)
        );
        assert_eq!(checked_plaintext_len(65515, 0), Ok(65519));
        assert_eq!(
            checked_plaintext_len(65516, 0),
            Err(WireError::OversizedPayload)
        );
    }
}
