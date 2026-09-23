//! TCP1 outer record framing codec.

use vela_proto::{MAX_NOISE_MESSAGE, MIN_TRANSPORT_CIPHERTEXT};

use crate::error::WireError;

/// Protocol phase for outer TCP1 framing validation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OuterPhase {
    /// Noise handshake phase (message length 1..=65,535).
    Handshake,
    /// Authenticated transport phase (ciphertext length 20..=65,535).
    Transport,
}

/// Validates that a message length is permitted for the given [`OuterPhase`].
///
/// # Errors
///
/// Returns [`WireError::InvalidOuterLength`] if:
/// - length is 0 (both Handshake and Transport require non-zero lengths),
/// - length is less than 20 for [`OuterPhase::Transport`], or
/// - length exceeds [`MAX_NOISE_MESSAGE`] (65,535).
pub fn validate_message_length(phase: OuterPhase, len: usize) -> Result<u16, WireError> {
    if len == 0 || len > MAX_NOISE_MESSAGE {
        return Err(WireError::InvalidOuterLength);
    }
    if matches!(phase, OuterPhase::Transport) && len < MIN_TRANSPORT_CIPHERTEXT {
        return Err(WireError::InvalidOuterLength);
    }
    #[allow(clippy::cast_possible_truncation)]
    Ok(len as u16)
}

/// Encodes a message with a 2-byte big-endian outer length prefix.
///
/// # Errors
///
/// Returns [`WireError::InvalidOuterLength`] if `message.len()` is invalid for `phase`,
/// or [`WireError::ArithmeticOverflow`] if buffer sizing overflows.
pub fn encode_outer(phase: OuterPhase, message: &[u8]) -> Result<Vec<u8>, WireError> {
    let len_u16 = validate_message_length(phase, message.len())?;
    let total_len = 2_usize
        .checked_add(message.len())
        .ok_or(WireError::ArithmeticOverflow)?;

    let mut out = Vec::with_capacity(total_len);
    out.extend_from_slice(&len_u16.to_be_bytes());
    out.extend_from_slice(message);
    Ok(out)
}

/// Decodes an exact complete TCP1 outer record.
///
/// Requires that `record.len() == 2 + declared_length` exactly.
///
/// # Errors
///
/// Returns [`WireError::Truncated`] if `record` has fewer than 2 bytes or fewer bytes
/// than declared.
/// Returns [`WireError::InvalidOuterLength`] if the declared length is invalid for `phase`
/// or if trailing unconsumed bytes exist in `record`.
pub fn decode_outer_exact(phase: OuterPhase, record: &[u8]) -> Result<&[u8], WireError> {
    if record.len() < 2 {
        return Err(WireError::Truncated);
    }
    let declared = u16::from_be_bytes([record[0], record[1]]) as usize;
    let _ = validate_message_length(phase, declared)?;

    let expected_len = 2_usize
        .checked_add(declared)
        .ok_or(WireError::ArithmeticOverflow)?;

    if record.len() < expected_len {
        return Err(WireError::Truncated);
    }
    if record.len() > expected_len {
        return Err(WireError::InvalidOuterLength);
    }

    Ok(&record[2..expected_len])
}
