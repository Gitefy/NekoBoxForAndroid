//! Authenticated record channel: `vela-crypto` AEAD + `vela-wire` outer framing.
//!
//! `TCP1` outer framing (spec §4): a 2-byte big-endian length prefix followed by
//! exactly that many bytes. Handshake flights use `OuterPhase::Handshake`
//! (length 1..=65,535); transport records use `OuterPhase::Transport`
//! (ciphertext length 20..=65,535).
//!
//! Fail-closed: every cryptographic, framing, or I/O failure is terminal.

use std::io::{Read, Write};

use vela_crypto::ClientHandshake;
use vela_crypto::transport::{ClientTransport, ServerTransport};
use vela_wire::outer::{OuterPhase, encode_outer, validate_message_length};

use crate::error::TransportError;

const LENGTH_PREFIX_LEN: usize = 2;
/// Coalesce encrypted records into fewer `write` syscalls on high-RTT paths.
const WRITE_COALESCE_THRESHOLD: usize = 32 * 1024;

/// Which side of the session this channel drives.
#[derive(Debug)]
enum CryptoSide {
    Client(ClientTransport),
    Server(ServerTransport),
}

impl CryptoSide {
    fn encrypt(&self, plaintext: &[u8], out: &mut [u8]) -> Result<usize, TransportError> {
        match self {
            Self::Client(t) => Ok(t.encrypt(plaintext, out)?),
            Self::Server(t) => Ok(t.encrypt(plaintext, out)?),
        }
    }

    fn decrypt(&self, ciphertext: &[u8], out: &mut [u8]) -> Result<usize, TransportError> {
        match self {
            Self::Client(t) => Ok(t.decrypt(ciphertext, out)?),
            Self::Server(t) => Ok(t.decrypt(ciphertext, out)?),
        }
    }

    fn rekey_send(&self) -> Result<(), TransportError> {
        match self {
            Self::Client(t) => Ok(t.rekey_send()?),
            Self::Server(t) => Ok(t.rekey_send()?),
        }
    }

    fn rekey_recv(&self) -> Result<(), TransportError> {
        match self {
            Self::Client(t) => Ok(t.rekey_recv()?),
            Self::Server(t) => Ok(t.rekey_recv()?),
        }
    }
}

/// Authenticated record exchange over any byte stream.
#[derive(Debug)]
pub struct RecordChannel<S> {
    io: S,
    crypto: CryptoSide,
    poisoned: bool,
    read_buf: Vec<u8>,
    write_buf: Vec<u8>,
}

impl<S: Read + Write> RecordChannel<S> {
    /// Wraps a completed client handshake into a record channel.
    ///
    /// # Errors
    /// [`TransportError::Crypto`] when the handshake did not complete.
    pub fn from_client_handshake(
        io: S,
        handshake: ClientHandshake,
    ) -> Result<Self, TransportError> {
        let transport = handshake.into_transport()?;
        Ok(Self {
            io,
            crypto: CryptoSide::Client(transport),
            poisoned: false,
            read_buf: Vec::new(),
            write_buf: Vec::new(),
        })
    }

    /// Wraps a completed server handshake into a record channel.
    ///
    /// # Errors
    /// [`TransportError::Crypto`] when the handshake did not complete.
    pub fn from_server_handshake(
        io: S,
        handshake: vela_crypto::ServerHandshake,
    ) -> Result<Self, TransportError> {
        let transport = handshake.into_transport()?;
        Ok(Self {
            io,
            crypto: CryptoSide::Server(transport),
            poisoned: false,
            read_buf: Vec::new(),
            write_buf: Vec::new(),
        })
    }

    /// Encrypts `plaintext`, queues one framed record, and flushes immediately.
    ///
    /// # Errors
    /// Terminal on any cryptographic, framing, or I/O failure.
    pub fn write_record(&mut self, plaintext: &[u8]) -> Result<(), TransportError> {
        self.write_record_unflushed(plaintext)?;
        self.flush()
    }

    /// Encrypts `plaintext` and appends one framed record to the write buffer.
    ///
    /// Call [`RecordChannel::flush`] before blocking on a read or when the
    /// peer must observe the bytes. Large buffers are drained automatically.
    ///
    /// # Errors
    /// Terminal on any cryptographic, framing, or I/O failure.
    pub fn write_record_unflushed(&mut self, plaintext: &[u8]) -> Result<(), TransportError> {
        self.ensure_live()?;
        let ciphertext_len = plaintext.len() + vela_proto::constants::VCP1_TAG_LEN;
        validate_message_length(OuterPhase::Transport, ciphertext_len)?;
        let mut ciphertext = vec![0u8; ciphertext_len];
        let n = self
            .crypto
            .encrypt(plaintext, &mut ciphertext)
            .inspect_err(|_| self.poisoned = true)?;
        ciphertext.truncate(n);
        let record = encode_outer(OuterPhase::Transport, &ciphertext)?;
        self.write_buf.extend_from_slice(&record);
        if self.write_buf.len() >= WRITE_COALESCE_THRESHOLD {
            self.drain_write_buf()?;
        }
        Ok(())
    }

    /// Writes any buffered ciphertext and flushes the underlying stream.
    ///
    /// # Errors
    /// Terminal on I/O failure.
    pub fn flush(&mut self) -> Result<(), TransportError> {
        self.ensure_live()?;
        self.drain_write_buf()?;
        self.io.flush().inspect_err(|_| self.poisoned = true)?;
        Ok(())
    }

    /// Reads one framed record, decrypts it, and returns the plaintext.
    ///
    /// Pending outbound bytes are flushed first so half-duplex peers observe
    /// prior writes before this side blocks on a read.
    ///
    /// # Errors
    /// Terminal on peer close, framing violation, or cryptographic failure.
    pub fn read_record(&mut self) -> Result<Vec<u8>, TransportError> {
        self.flush()?;
        let mut temp = [0u8; 16384];

        loop {
            if self.read_buf.len() >= LENGTH_PREFIX_LEN {
                let declared = u16::from_be_bytes([self.read_buf[0], self.read_buf[1]]) as usize;
                validate_message_length(OuterPhase::Transport, declared)
                    .inspect_err(|_| self.poisoned = true)?;
                let total_len = LENGTH_PREFIX_LEN + declared;

                if self.read_buf.len() >= total_len {
                    let record: Vec<u8> = self.read_buf.drain(..total_len).collect();
                    let ciphertext =
                        vela_wire::outer::decode_outer_exact(OuterPhase::Transport, &record)?;
                    let mut plaintext =
                        vec![0u8; ciphertext.len() - vela_proto::constants::VCP1_TAG_LEN];
                    let n = self
                        .crypto
                        .decrypt(ciphertext, &mut plaintext)
                        .inspect_err(|_| self.poisoned = true)?;
                    plaintext.truncate(n);
                    return Ok(plaintext);
                }
            }

            match self.io.read(&mut temp) {
                Ok(0) => {
                    self.poisoned = true;
                    return Err(TransportError::PeerClosed);
                }
                Ok(n) => {
                    self.read_buf.extend_from_slice(&temp[..n]);
                }
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(e) => {
                    if !matches!(
                        e.kind(),
                        std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock
                    ) {
                        self.poisoned = true;
                    }
                    return Err(TransportError::Io(e));
                }
            }
        }
    }

    /// Rekeys the send direction (after `KEY_UPDATE` is written, spec §8).
    ///
    /// # Errors
    /// [`TransportError::Crypto`] on a poisoned session.
    pub fn rekey_send(&mut self) -> Result<(), TransportError> {
        self.ensure_live()?;
        self.crypto.rekey_send()
    }

    /// Rekeys the receive direction (after `KEY_UPDATE` is processed, spec §8).
    ///
    /// # Errors
    /// [`TransportError::Crypto`] on a poisoned session.
    pub fn rekey_recv(&mut self) -> Result<(), TransportError> {
        self.ensure_live()?;
        self.crypto.rekey_recv()
    }

    /// Returns a mutable reference to the underlying byte stream.
    pub(crate) fn inner_mut(&mut self) -> &mut S {
        &mut self.io
    }

    fn drain_write_buf(&mut self) -> Result<(), TransportError> {
        if self.write_buf.is_empty() {
            return Ok(());
        }
        self.io
            .write_all(&self.write_buf)
            .inspect_err(|_| self.poisoned = true)?;
        self.write_buf.clear();
        Ok(())
    }
}

impl<S: Read + Write> RecordChannel<S> {
    pub(crate) fn is_poisoned(&self) -> bool {
        self.poisoned
    }

    fn ensure_live(&self) -> Result<(), TransportError> {
        if self.poisoned {
            return Err(TransportError::Crypto(
                vela_crypto::CryptoError::SessionPoisoned,
            ));
        }
        Ok(())
    }
}
