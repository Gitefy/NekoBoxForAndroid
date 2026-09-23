//! Driving the Noise XK handshake over any byte stream (spec §5).
//!
//! Handshake flights are exchanged as `OuterPhase::Handshake` records. The
//! exact flight sizes (M1 = 50, M2 = 50, M3 = 66) are enforced by
//! `vela-crypto`; this module only moves bytes and never inspects them.

use std::io::{Read, Write};

use vela_crypto::keys::{
    ClientStaticPrivateKey, ClientStaticPublicKey, HandshakeHash, ServerStaticPrivateKey,
    ServerStaticPublicKey,
};
use vela_crypto::{ClientHandshake, ServerHandshake};
use vela_wire::outer::{OuterPhase, decode_outer_exact, encode_outer, validate_message_length};

use crate::error::TransportError;
use crate::record::RecordChannel;
use crate::replay::HandshakeReplayGuard;

const LENGTH_PREFIX_LEN: usize = 2;
const MAX_HANDSHAKE_RECORD: usize = 128;

/// Performs the client side of the handshake and returns the established
/// record channel plus the channel binding.
///
/// # Errors
/// Terminal on any I/O, framing, or cryptographic failure.
pub fn client_handshake<S: Read + Write>(
    mut io: S,
    client_static: &ClientStaticPrivateKey,
    server_static: &ServerStaticPublicKey,
) -> Result<(RecordChannel<S>, HandshakeHash), TransportError> {
    let mut handshake = ClientHandshake::new(client_static, server_static)?;

    let mut m1 = [0u8; 64];
    let n1 = handshake.write_m1(&mut m1)?;
    write_record(&mut io, &m1[..n1])?;

    let mut m2 = vec![0u8; MAX_HANDSHAKE_RECORD];
    let n2 = read_record(&mut io, &mut m2)?;
    handshake.read_m2(&m2[..n2])?;

    let mut m3 = [0u8; 80];
    let n3 = handshake.write_m3(&mut m3)?;
    write_record(&mut io, &m3[..n3])?;

    let binding = handshake.handshake_hash()?;
    let channel = RecordChannel::from_client_handshake(io, handshake)?;
    Ok((channel, binding))
}

/// Performs the server side of the handshake and returns the established
/// record channel plus the channel binding.
///
/// # Errors
/// Terminal on any I/O, framing, or cryptographic failure.
pub fn server_handshake<S: Read + Write>(
    io: S,
    server_static: &ServerStaticPrivateKey,
) -> Result<(RecordChannel<S>, HandshakeHash, ClientStaticPublicKey), TransportError> {
    server_handshake_with_replay_guard(io, server_static, None)
}

/// Server handshake with optional first-flight replay suppression.
///
/// When `replay_guard` is provided, an exact M1 replay fails closed without
/// writing M2 (same observable surface as an invalid probe).
///
/// # Errors
/// Terminal on any I/O, framing, cryptographic, or replay failure.
pub fn server_handshake_with_replay_guard<S: Read + Write>(
    mut io: S,
    server_static: &ServerStaticPrivateKey,
    replay_guard: Option<&HandshakeReplayGuard>,
) -> Result<(RecordChannel<S>, HandshakeHash, ClientStaticPublicKey), TransportError> {
    let mut handshake = ServerHandshake::new(server_static)?;

    let mut m1 = vec![0u8; MAX_HANDSHAKE_RECORD];
    let n1 = read_record(&mut io, &mut m1)?;
    // Invalid flights must not consume cache capacity or evict valid entries.
    // Check-and-insert remains atomic and happens before any M2 response.
    handshake.read_m1(&m1[..n1])?;
    if let Some(guard) = replay_guard {
        if !guard.admit(&m1[..n1]) {
            return Err(TransportError::HandshakeReplay);
        }
    }

    let mut m2 = [0u8; 64];
    let n2 = handshake.write_m2(&mut m2)?;
    write_record(&mut io, &m2[..n2])?;

    let mut m3 = vec![0u8; MAX_HANDSHAKE_RECORD];
    let n3 = read_record(&mut io, &mut m3)?;
    let client_static = handshake.read_m3(&m3[..n3])?;

    let binding = handshake.handshake_hash()?;
    let channel = RecordChannel::from_server_handshake(io, handshake)?;
    Ok((channel, binding, client_static))
}

fn write_record<S: Write>(io: &mut S, message: &[u8]) -> Result<(), TransportError> {
    let record = encode_outer(OuterPhase::Handshake, message)?;
    io.write_all(&record)?;
    io.flush()?;
    Ok(())
}

fn read_record<S: Read>(io: &mut S, out: &mut [u8]) -> Result<usize, TransportError> {
    let mut prefix = [0u8; LENGTH_PREFIX_LEN];
    read_exact_or_peer_closed(io, &mut prefix)?;
    let declared = u16::from_be_bytes(prefix) as usize;
    validate_message_length(OuterPhase::Handshake, declared)?;
    if declared > out.len() {
        return Err(TransportError::Wire(
            vela_wire::WireError::InvalidBodyLength,
        ));
    }
    let mut record = vec![0u8; LENGTH_PREFIX_LEN + declared];
    record[..LENGTH_PREFIX_LEN].copy_from_slice(&prefix);
    read_exact_or_peer_closed(io, &mut record[LENGTH_PREFIX_LEN..])?;
    let message = decode_outer_exact(OuterPhase::Handshake, &record)?;
    out[..message.len()].copy_from_slice(message);
    Ok(message.len())
}

fn read_exact_or_peer_closed<S: Read>(io: &mut S, buf: &mut [u8]) -> Result<(), TransportError> {
    let mut filled = 0;
    while filled < buf.len() {
        match io.read(&mut buf[filled..]) {
            Ok(0) => return Err(TransportError::PeerClosed),
            Ok(n) => filled += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(TransportError::Io(e)),
        }
    }
    Ok(())
}
