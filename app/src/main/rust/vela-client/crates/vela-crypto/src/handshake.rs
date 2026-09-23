//! Role-specific Noise XK handshake wrappers for VCP-1.
//!
//! The client is the Noise Initiator; the server is the Noise Responder. Role
//! reversal is impossible at the type level. Production constructors generate
//! fresh ephemerals internally via the OS CSPRNG and never accept
//! caller-controlled ephemeral input. Deterministic ephemeral injection exists
//! only behind `#[cfg(test)]` constructors used by the crate-internal golden
//! harness.
//!
//! Handshake flights are exactly sized by the frozen specification:
//! M1 = 50 bytes, M2 = 50 bytes, M3 = 66 bytes. Each flight carries exactly
//! the 2-byte empty handshake payload (`0x00 0x00`).

use snow::{Builder, HandshakeState};

use crate::error::CryptoError;
use crate::keys::{
    ClientStaticPrivateKey, ClientStaticPublicKey, HandshakeHash, ServerStaticPrivateKey,
    ServerStaticPublicKey,
};

/// Frozen VCP-1 Noise protocol name.
pub(crate) const PROTOCOL_NAME: &str = "Noise_XK_25519_ChaChaPoly_SHA256";

/// Frozen 12-byte handshake prologue: `VELA-P1\0` + protocol/vcp/transport/policy versions.
pub(crate) const PROLOGUE: [u8; 12] = [
    0x56, 0x45, 0x4C, 0x41, 0x2D, 0x50, 0x31, 0x00, 0x01, 0x01, 0x01, 0x01,
];

/// Frozen handshake payload: `body_len: u16 = 0`, empty body, zero padding.
pub(crate) const HANDSHAKE_PAYLOAD: [u8; 2] = [0x00, 0x00];

/// Frozen M1 (`CLIENT_HELLO`) flight length.
pub(crate) const M1_LEN: usize = 50;

/// Frozen M2 (`SERVER_HELLO`) flight length.
pub(crate) const M2_LEN: usize = 50;

/// Frozen M3 (`CLIENT_FINISH`) flight length.
pub(crate) const M3_LEN: usize = 66;

/// Maps a Noise engine failure onto the partitioned error taxonomy.
pub(crate) fn map_snow_error(err: &snow::Error) -> CryptoError {
    match err {
        // DH failure and AEAD decryption failure are authentication failures.
        snow::Error::Decrypt | snow::Error::Dh => CryptoError::AuthenticationFailed,
        _ => CryptoError::EngineFailure,
    }
}

fn build_initiator(
    client_static: &ClientStaticPrivateKey,
    server_static: &ServerStaticPublicKey,
    fixed_ephemeral: Option<&[u8; 32]>,
) -> Result<HandshakeState, CryptoError> {
    let params = PROTOCOL_NAME
        .parse()
        .map_err(|_| CryptoError::EngineFailure)?;
    let mut builder = Builder::new(params)
        .prologue(&PROLOGUE)
        .map_err(|e| map_snow_error(&e))?
        .local_private_key(client_static.secret_bytes())
        .map_err(|e| map_snow_error(&e))?
        .remote_public_key(server_static.as_bytes())
        .map_err(|e| map_snow_error(&e))?;
    if let Some(ephemeral) = fixed_ephemeral {
        // Test-only path: crate-internal, `#[cfg(test)]` callers exclusively.
        builder = builder.fixed_ephemeral_key_for_testing_only(ephemeral);
    }
    builder.build_initiator().map_err(|e| map_snow_error(&e))
}

fn build_responder(
    server_static: &ServerStaticPrivateKey,
    fixed_ephemeral: Option<&[u8; 32]>,
) -> Result<HandshakeState, CryptoError> {
    let params = PROTOCOL_NAME
        .parse()
        .map_err(|_| CryptoError::EngineFailure)?;
    let mut builder = Builder::new(params)
        .prologue(&PROLOGUE)
        .map_err(|e| map_snow_error(&e))?
        .local_private_key(server_static.secret_bytes())
        .map_err(|e| map_snow_error(&e))?;
    if let Some(ephemeral) = fixed_ephemeral {
        builder = builder.fixed_ephemeral_key_for_testing_only(ephemeral);
    }
    builder.build_responder().map_err(|e| map_snow_error(&e))
}

/// Client-side (Noise Initiator) handshake state machine.
#[derive(Debug)]
pub struct ClientHandshake {
    pub(crate) inner: HandshakeState,
    pub(crate) step: ClientHandshakeStep,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ClientHandshakeStep {
    ExpectWriteM1,
    ExpectReadM2,
    ExpectWriteM3,
    Complete,
    Failed,
}

impl ClientHandshake {
    /// Constructs a client handshake with a fresh OS-CSPRNG ephemeral.
    ///
    /// # Errors
    /// Returns [`CryptoError::EngineFailure`] if the underlying Noise engine
    /// cannot be initialized.
    pub fn new(
        client_static: &ClientStaticPrivateKey,
        server_static: &ServerStaticPublicKey,
    ) -> Result<Self, CryptoError> {
        Ok(Self {
            inner: build_initiator(client_static, server_static, None)?,
            step: ClientHandshakeStep::ExpectWriteM1,
        })
    }

    fn fail(&mut self, err: CryptoError) -> CryptoError {
        self.step = ClientHandshakeStep::Failed;
        err
    }

    fn ensure_active(&self, expected: ClientHandshakeStep) -> Result<(), CryptoError> {
        if self.step == ClientHandshakeStep::Failed {
            return Err(CryptoError::SessionPoisoned);
        }
        if self.step != expected {
            return Err(CryptoError::InvalidStateProgression);
        }
        Ok(())
    }

    /// Writes M1 (`CLIENT_HELLO`). Exactly 50 bytes into `out`.
    ///
    /// # Errors
    /// [`CryptoError::InvalidStateProgression`] when out of sequence,
    /// [`CryptoError::BufferTooSmall`] when `out` holds fewer than 50 bytes
    /// (recoverable), and terminal errors on engine failure.
    pub fn write_m1(&mut self, out: &mut [u8]) -> Result<usize, CryptoError> {
        self.ensure_active(ClientHandshakeStep::ExpectWriteM1)?;
        if out.len() < M1_LEN {
            return Err(CryptoError::BufferTooSmall {
                required: M1_LEN,
                provided: out.len(),
            });
        }
        let n = self
            .inner
            .write_message(&HANDSHAKE_PAYLOAD, out)
            .map_err(|e| self.fail(map_snow_error(&e)))?;
        if n != M1_LEN {
            return Err(self.fail(CryptoError::EngineFailure));
        }
        self.step = ClientHandshakeStep::ExpectReadM2;
        Ok(n)
    }

    /// Reads M2 (`SERVER_HELLO`). Exactly 50 bytes.
    ///
    /// # Errors
    /// [`CryptoError::FlightLengthMismatch`] for wrong flight size (terminal),
    /// [`CryptoError::AuthenticationFailed`] on tag failure (terminal),
    /// [`CryptoError::PayloadMismatch`] on non-frozen payload (terminal).
    pub fn read_m2(&mut self, m2: &[u8]) -> Result<(), CryptoError> {
        self.ensure_active(ClientHandshakeStep::ExpectReadM2)?;
        if m2.len() != M2_LEN {
            return Err(self.fail(CryptoError::FlightLengthMismatch {
                expected: M2_LEN,
                actual: m2.len(),
            }));
        }
        let mut payload = [0u8; 8];
        let n = self
            .inner
            .read_message(m2, &mut payload)
            .map_err(|e| self.fail(map_snow_error(&e)))?;
        if n != HANDSHAKE_PAYLOAD.len() || payload[..n] != HANDSHAKE_PAYLOAD {
            return Err(self.fail(CryptoError::PayloadMismatch));
        }
        self.step = ClientHandshakeStep::ExpectWriteM3;
        Ok(())
    }

    /// Writes M3 (`CLIENT_FINISH`). Exactly 66 bytes into `out`.
    ///
    /// # Errors
    /// See [`ClientHandshake::write_m1`]; M3 requires 66 bytes.
    pub fn write_m3(&mut self, out: &mut [u8]) -> Result<usize, CryptoError> {
        self.ensure_active(ClientHandshakeStep::ExpectWriteM3)?;
        if out.len() < M3_LEN {
            return Err(CryptoError::BufferTooSmall {
                required: M3_LEN,
                provided: out.len(),
            });
        }
        let n = self
            .inner
            .write_message(&HANDSHAKE_PAYLOAD, out)
            .map_err(|e| self.fail(map_snow_error(&e)))?;
        if n != M3_LEN {
            return Err(self.fail(CryptoError::EngineFailure));
        }
        self.step = ClientHandshakeStep::Complete;
        Ok(n)
    }

    /// Returns the 32-byte handshake hash used as channel binding.
    ///
    /// # Errors
    /// [`CryptoError::InvalidStateProgression`] before M3 completion or after
    /// a terminal failure.
    pub fn handshake_hash(&self) -> Result<HandshakeHash, CryptoError> {
        if self.step == ClientHandshakeStep::Failed {
            return Err(CryptoError::SessionPoisoned);
        }
        if self.step != ClientHandshakeStep::Complete {
            return Err(CryptoError::InvalidStateProgression);
        }
        let hash = self.inner.get_handshake_hash();
        if hash.len() != 32 {
            return Err(CryptoError::EngineFailure);
        }
        let mut bytes = [0u8; 32];
        bytes.copy_from_slice(hash);
        Ok(HandshakeHash::new(bytes))
    }

    #[cfg(test)]
    pub(crate) fn new_with_fixed_ephemeral_for_testing(
        client_static: &ClientStaticPrivateKey,
        server_static: &ServerStaticPublicKey,
        client_ephemeral: &[u8; 32],
    ) -> Result<Self, CryptoError> {
        Ok(Self {
            inner: build_initiator(client_static, server_static, Some(client_ephemeral))?,
            step: ClientHandshakeStep::ExpectWriteM1,
        })
    }
}

/// Server-side (Noise Responder) handshake state machine.
#[derive(Debug)]
pub struct ServerHandshake {
    pub(crate) inner: HandshakeState,
    pub(crate) step: ServerHandshakeStep,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ServerHandshakeStep {
    ExpectReadM1,
    ExpectWriteM2,
    ExpectReadM3,
    Complete,
    Failed,
}

impl ServerHandshake {
    /// Constructs a server handshake with a fresh OS-CSPRNG ephemeral.
    ///
    /// # Errors
    /// Returns [`CryptoError::EngineFailure`] if the underlying Noise engine
    /// cannot be initialized.
    pub fn new(server_static: &ServerStaticPrivateKey) -> Result<Self, CryptoError> {
        Ok(Self {
            inner: build_responder(server_static, None)?,
            step: ServerHandshakeStep::ExpectReadM1,
        })
    }

    fn fail(&mut self, err: CryptoError) -> CryptoError {
        self.step = ServerHandshakeStep::Failed;
        err
    }

    fn ensure_active(&self, expected: ServerHandshakeStep) -> Result<(), CryptoError> {
        if self.step == ServerHandshakeStep::Failed {
            return Err(CryptoError::SessionPoisoned);
        }
        if self.step != expected {
            return Err(CryptoError::InvalidStateProgression);
        }
        Ok(())
    }

    /// Reads M1 (`CLIENT_HELLO`). Exactly 50 bytes.
    ///
    /// # Errors
    /// Terminal on any flight-size, authentication, or payload mismatch.
    pub fn read_m1(&mut self, m1: &[u8]) -> Result<(), CryptoError> {
        self.ensure_active(ServerHandshakeStep::ExpectReadM1)?;
        if m1.len() != M1_LEN {
            return Err(self.fail(CryptoError::FlightLengthMismatch {
                expected: M1_LEN,
                actual: m1.len(),
            }));
        }
        let mut payload = [0u8; 8];
        let n = self
            .inner
            .read_message(m1, &mut payload)
            .map_err(|e| self.fail(map_snow_error(&e)))?;
        if n != HANDSHAKE_PAYLOAD.len() || payload[..n] != HANDSHAKE_PAYLOAD {
            return Err(self.fail(CryptoError::PayloadMismatch));
        }
        self.step = ServerHandshakeStep::ExpectWriteM2;
        Ok(())
    }

    /// Writes M2 (`SERVER_HELLO`). Exactly 50 bytes into `out`.
    ///
    /// # Errors
    /// [`CryptoError::InvalidStateProgression`] when out of sequence,
    /// [`CryptoError::BufferTooSmall`] when `out` holds fewer than 50 bytes.
    pub fn write_m2(&mut self, out: &mut [u8]) -> Result<usize, CryptoError> {
        self.ensure_active(ServerHandshakeStep::ExpectWriteM2)?;
        if out.len() < M2_LEN {
            return Err(CryptoError::BufferTooSmall {
                required: M2_LEN,
                provided: out.len(),
            });
        }
        let n = self
            .inner
            .write_message(&HANDSHAKE_PAYLOAD, out)
            .map_err(|e| self.fail(map_snow_error(&e)))?;
        if n != M2_LEN {
            return Err(self.fail(CryptoError::EngineFailure));
        }
        self.step = ServerHandshakeStep::ExpectReadM3;
        Ok(n)
    }

    /// Reads M3 (`CLIENT_FINISH`). Exactly 66 bytes. Returns the authenticated
    /// client static public key.
    ///
    /// # Errors
    /// Terminal on any flight-size, authentication, or payload mismatch.
    pub fn read_m3(&mut self, m3: &[u8]) -> Result<ClientStaticPublicKey, CryptoError> {
        self.ensure_active(ServerHandshakeStep::ExpectReadM3)?;
        if m3.len() != M3_LEN {
            return Err(self.fail(CryptoError::FlightLengthMismatch {
                expected: M3_LEN,
                actual: m3.len(),
            }));
        }
        let mut payload = [0u8; 8];
        let n = self
            .inner
            .read_message(m3, &mut payload)
            .map_err(|e| self.fail(map_snow_error(&e)))?;
        if n != HANDSHAKE_PAYLOAD.len() || payload[..n] != HANDSHAKE_PAYLOAD {
            return Err(self.fail(CryptoError::PayloadMismatch));
        }
        let remote = self
            .inner
            .get_remote_static()
            .ok_or(CryptoError::EngineFailure)?;
        if remote.len() != 32 {
            return Err(self.fail(CryptoError::EngineFailure));
        }
        let mut bytes = [0u8; 32];
        bytes.copy_from_slice(remote);
        self.step = ServerHandshakeStep::Complete;
        Ok(ClientStaticPublicKey::new(bytes))
    }

    /// Returns the 32-byte handshake hash used as channel binding.
    ///
    /// # Errors
    /// [`CryptoError::InvalidStateProgression`] before M3 completion or after
    /// a terminal failure.
    pub fn handshake_hash(&self) -> Result<HandshakeHash, CryptoError> {
        if self.step == ServerHandshakeStep::Failed {
            return Err(CryptoError::SessionPoisoned);
        }
        if self.step != ServerHandshakeStep::Complete {
            return Err(CryptoError::InvalidStateProgression);
        }
        let hash = self.inner.get_handshake_hash();
        if hash.len() != 32 {
            return Err(CryptoError::EngineFailure);
        }
        let mut bytes = [0u8; 32];
        bytes.copy_from_slice(hash);
        Ok(HandshakeHash::new(bytes))
    }

    #[cfg(test)]
    pub(crate) fn new_with_fixed_ephemeral_for_testing(
        server_static: &ServerStaticPrivateKey,
        server_ephemeral: &[u8; 32],
    ) -> Result<Self, CryptoError> {
        Ok(Self {
            inner: build_responder(server_static, Some(server_ephemeral))?,
            step: ServerHandshakeStep::ExpectReadM1,
        })
    }
}
