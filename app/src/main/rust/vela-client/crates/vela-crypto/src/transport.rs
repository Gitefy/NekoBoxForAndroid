//! Directional transport encryption with a shared session liveness guard.
//!
//! After the Noise handshake completes, `Split()` yields two unidirectional
//! cipher states: client->server and server->client. This module wraps them in
//! role-specific types so direction confusion is impossible at the type level.
//!
//! Terminal invariants:
//! - Any terminal error permanently poisons the whole session. Both
//!   directional handles observe the poison; a failure on one direction
//!   invalidates the other.
//! - On any decryption failure the caller's output buffer is zeroed before
//!   returning; no unauthenticated or partial plaintext is ever exposed.
//! - Poisoned sessions are never recoverable; a new handshake with fresh
//!   ephemerals is required.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use snow::TransportState;

use crate::error::CryptoError;
use crate::handshake::{
    ClientHandshake, ClientHandshakeStep, ServerHandshake, ServerHandshakeStep,
};

/// Frozen transport plaintext bounds: `MIN_VELA_PLAINTEXT`.
pub(crate) const MIN_PLAINTEXT: usize = 4;

/// Frozen transport plaintext bounds: `MAX_VELA_PLAINTEXT`.
pub(crate) const MAX_PLAINTEXT: usize = 65519;

/// Frozen transport ciphertext bounds: `MIN_TRANSPORT_CIPHERTEXT`.
pub(crate) const MIN_CIPHERTEXT: usize = 20;

/// Frozen transport ciphertext bounds: `MAX_NOISE_MESSAGE`.
pub(crate) const MAX_CIPHERTEXT: usize = 65535;

/// AEAD tag length: `VCP1_TAG_LEN`.
pub(crate) const TAG_LEN: usize = 16;

/// Shared liveness guard tracking terminal cryptographic failure.
#[derive(Debug, Clone, Default)]
pub struct SessionLivenessGuard {
    poisoned: Arc<AtomicBool>,
}

impl SessionLivenessGuard {
    /// Creates a live (unpoisoned) guard.
    #[must_use]
    pub fn new() -> Self {
        Self {
            poisoned: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Returns `true` once the session has been terminally poisoned.
    #[must_use]
    pub fn is_poisoned(&self) -> bool {
        self.poisoned.load(Ordering::SeqCst)
    }

    /// Permanently poisons the session.
    fn poison(&self) {
        self.poisoned.store(true, Ordering::SeqCst);
    }
}

/// Internal shared transport session state.
#[derive(Debug)]
struct TransportSessionInner {
    state: Mutex<TransportState>,
}

impl TransportSessionInner {
    fn lock_or_poison(
        &self,
        liveness: Option<&SessionLivenessGuard>,
    ) -> Result<MutexGuard<'_, TransportState>, CryptoError> {
        if let Ok(guard) = self.state.lock() {
            return Ok(guard);
        }
        // A panic while holding the lock leaves the session in an
        // unverifiable state: fail closed, permanently.
        if let Some(l) = liveness {
            l.poison();
        }
        Err(CryptoError::EngineFailure)
    }
}

/// Shared internals for the client and server transport wrappers.
#[derive(Debug)]
struct TransportCore {
    inner: Arc<TransportSessionInner>,
    liveness: SessionLivenessGuard,
}

impl TransportCore {
    fn encrypt(&self, plaintext: &[u8], out: &mut [u8]) -> Result<usize, CryptoError> {
        if self.liveness.is_poisoned() {
            return Err(CryptoError::SessionPoisoned);
        }
        if !(MIN_PLAINTEXT..=MAX_PLAINTEXT).contains(&plaintext.len()) {
            return Err(CryptoError::PlaintextOutOfBounds(plaintext.len()));
        }
        let required = plaintext.len() + TAG_LEN;
        if out.len() < required {
            return Err(CryptoError::BufferTooSmall {
                required,
                provided: out.len(),
            });
        }
        let mut guard = self.inner.lock_or_poison(Some(&self.liveness))?;
        if guard.sending_nonce() == u64::MAX {
            self.liveness.poison();
            return Err(CryptoError::NonceExhausted);
        }
        match guard.write_message(plaintext, out) {
            Ok(n) => Ok(n),
            Err(e) => {
                let mapped = crate::handshake::map_snow_error(&e);
                if mapped.is_terminal() {
                    self.liveness.poison();
                }
                Err(mapped)
            }
        }
    }

    fn decrypt(&self, ciphertext: &[u8], out: &mut [u8]) -> Result<usize, CryptoError> {
        if self.liveness.is_poisoned() {
            out.fill(0);
            return Err(CryptoError::SessionPoisoned);
        }
        if !(MIN_CIPHERTEXT..=MAX_CIPHERTEXT).contains(&ciphertext.len()) {
            out.fill(0);
            return Err(CryptoError::CiphertextOutOfBounds(ciphertext.len()));
        }
        let required = ciphertext.len() - TAG_LEN;
        if out.len() < required {
            return Err(CryptoError::BufferTooSmall {
                required,
                provided: out.len(),
            });
        }
        let mut guard = self.inner.lock_or_poison(Some(&self.liveness))?;
        if guard.receiving_nonce() == u64::MAX {
            self.liveness.poison();
            out.fill(0);
            return Err(CryptoError::NonceExhausted);
        }
        match guard.read_message(ciphertext, out) {
            Ok(n) => Ok(n),
            Err(e) => {
                // No unauthenticated or partial plaintext may be exposed.
                out.fill(0);
                let mapped = crate::handshake::map_snow_error(&e);
                if mapped.is_terminal() {
                    self.liveness.poison();
                }
                Err(mapped)
            }
        }
    }

    fn rekey_send(&self) -> Result<(), CryptoError> {
        if self.liveness.is_poisoned() {
            return Err(CryptoError::SessionPoisoned);
        }
        let mut guard = self.inner.lock_or_poison(Some(&self.liveness))?;
        guard.rekey_outgoing();
        Ok(())
    }

    fn rekey_recv(&self) -> Result<(), CryptoError> {
        if self.liveness.is_poisoned() {
            return Err(CryptoError::SessionPoisoned);
        }
        let mut guard = self.inner.lock_or_poison(Some(&self.liveness))?;
        guard.rekey_incoming();
        Ok(())
    }

    fn send_nonce(&self) -> Result<u64, CryptoError> {
        if self.liveness.is_poisoned() {
            return Err(CryptoError::SessionPoisoned);
        }
        let guard = self.inner.lock_or_poison(Some(&self.liveness))?;
        Ok(guard.sending_nonce())
    }

    fn recv_nonce(&self) -> Result<u64, CryptoError> {
        if self.liveness.is_poisoned() {
            return Err(CryptoError::SessionPoisoned);
        }
        let guard = self.inner.lock_or_poison(Some(&self.liveness))?;
        Ok(guard.receiving_nonce())
    }
}

macro_rules! directional_handle {
    ($(#[$meta:meta])* $name:ident) => {
        $(#[$meta])*
        #[derive(Debug, Clone)]
        pub struct $name {
            core: Arc<TransportCore>,
        }

        impl $name {
            /// See the corresponding transport wrapper method.
            ///
            /// # Errors
            /// Follows the transport wrapper error semantics, including
            /// terminal poisoning.
            pub fn encrypt(&self, plaintext: &[u8], out: &mut [u8]) -> Result<usize, CryptoError> {
                self.core.encrypt(plaintext, out)
            }

            /// See the corresponding transport wrapper method.
            ///
            /// # Errors
            /// Follows the transport wrapper error semantics, including
            /// terminal poisoning and output-buffer zeroing.
            pub fn decrypt(&self, ciphertext: &[u8], out: &mut [u8]) -> Result<usize, CryptoError> {
                self.core.decrypt(ciphertext, out)
            }

            /// Returns the current outgoing nonce for this session's send direction.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn send_nonce(&self) -> Result<u64, CryptoError> {
                self.core.send_nonce()
            }

            /// Returns the current incoming nonce for this session's recv direction.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn recv_nonce(&self) -> Result<u64, CryptoError> {
                self.core.recv_nonce()
            }

            /// Rekeys the send direction of this handle's session.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn rekey_send(&self) -> Result<(), CryptoError> {
                self.core.rekey_send()
            }

            /// Rekeys the recv direction of this handle's session.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn rekey_recv(&self) -> Result<(), CryptoError> {
                self.core.rekey_recv()
            }
        }
    };
}

directional_handle! {
    /// Client send handle (client -> server) from a split transport.
    ClientSender
}

directional_handle! {
    /// Client receive handle (server -> client) from a split transport.
    ClientReceiver
}

directional_handle! {
    /// Server send handle (server -> client) from a split transport.
    ServerSender
}

directional_handle! {
    /// Server receive handle (client -> server) from a split transport.
    ServerReceiver
}

/// Client-side transport cryptographic state.
///
/// Send = client -> server; Recv = server -> client.
#[derive(Debug, Clone)]
pub struct ClientTransport {
    core: Arc<TransportCore>,
}

/// Server-side transport cryptographic state.
///
/// Send = server -> client; Recv = client -> server.
#[derive(Debug, Clone)]
pub struct ServerTransport {
    core: Arc<TransportCore>,
}

macro_rules! transport_impl {
    ($(#[$meta:meta])* $name:ident, $sender:ident, $receiver:ident) => {
        impl $name {
            /// Encrypts an outgoing transport record.
            ///
            /// # Errors
            /// [`CryptoError::PlaintextOutOfBounds`] outside 4..=65519 bytes
            /// (recoverable), [`CryptoError::BufferTooSmall`] (recoverable),
            /// [`CryptoError::SessionPoisoned`] after terminal failure, and
            /// terminal errors ([`CryptoError::NonceExhausted`],
            /// [`CryptoError::EngineFailure`]) that poison the session.
            pub fn encrypt(&self, plaintext: &[u8], out: &mut [u8]) -> Result<usize, CryptoError> {
                self.core.encrypt(plaintext, out)
            }

            /// Decrypts an incoming transport record.
            ///
            /// # Errors
            /// [`CryptoError::CiphertextOutOfBounds`] outside 20..=65535 bytes,
            /// [`CryptoError::BufferTooSmall`] (recoverable),
            /// [`CryptoError::AuthenticationFailed`] on tag failure (terminal),
            /// [`CryptoError::SessionPoisoned`] after terminal failure. On any
            /// error the output buffer is zeroed.
            pub fn decrypt(&self, ciphertext: &[u8], out: &mut [u8]) -> Result<usize, CryptoError> {
                self.core.decrypt(ciphertext, out)
            }

            /// Rekeys the outgoing (send) cipher state without resetting nonces.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn rekey_send(&self) -> Result<(), CryptoError> {
                self.core.rekey_send()
            }

            /// Rekeys the incoming (recv) cipher state without resetting nonces.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn rekey_recv(&self) -> Result<(), CryptoError> {
                self.core.rekey_recv()
            }

            /// Returns the current outgoing nonce.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn send_nonce(&self) -> Result<u64, CryptoError> {
                self.core.send_nonce()
            }

            /// Returns the current incoming nonce.
            ///
            /// # Errors
            /// [`CryptoError::SessionPoisoned`] on a poisoned session.
            pub fn recv_nonce(&self) -> Result<u64, CryptoError> {
                self.core.recv_nonce()
            }

            /// Splits the transport into independent directional handles that
            /// share the session liveness guard.
            #[must_use]
            pub fn into_split(self) -> ($sender, $receiver) {
                let sender = $sender {
                    core: Arc::clone(&self.core),
                };
                let receiver = $receiver {
                    core: Arc::clone(&self.core),
                };
                (sender, receiver)
            }
        }
    };
}

transport_impl! {
    /// Client-side transport cryptographic state.
    ClientTransport, ClientSender, ClientReceiver
}

transport_impl! {
    /// Server-side transport cryptographic state.
    ServerTransport, ServerSender, ServerReceiver
}

fn transport_from_handshake(state: snow::HandshakeState) -> Result<TransportCore, CryptoError> {
    let transport = state
        .into_transport_mode()
        .map_err(|e| crate::handshake::map_snow_error(&e))?;
    Ok(TransportCore {
        inner: Arc::new(TransportSessionInner {
            state: Mutex::new(transport),
        }),
        liveness: SessionLivenessGuard::new(),
    })
}

impl ClientHandshake {
    /// Transitions the completed handshake into transport encryption mode.
    ///
    /// # Errors
    /// [`CryptoError::InvalidStateProgression`] before M3 completion,
    /// [`CryptoError::SessionPoisoned`] after terminal failure,
    /// [`CryptoError::EngineFailure`] on engine transition failure.
    pub fn into_transport(self) -> Result<ClientTransport, CryptoError> {
        if self.step == ClientHandshakeStep::Failed {
            return Err(CryptoError::SessionPoisoned);
        }
        if self.step != ClientHandshakeStep::Complete {
            return Err(CryptoError::InvalidStateProgression);
        }
        let core = transport_from_handshake(self.inner)?;
        Ok(ClientTransport {
            core: Arc::new(core),
        })
    }
}

impl ServerHandshake {
    /// Transitions the completed handshake into transport decryption mode.
    ///
    /// # Errors
    /// [`CryptoError::InvalidStateProgression`] before M3 completion,
    /// [`CryptoError::SessionPoisoned`] after terminal failure,
    /// [`CryptoError::EngineFailure`] on engine transition failure.
    pub fn into_transport(self) -> Result<ServerTransport, CryptoError> {
        if self.step == ServerHandshakeStep::Failed {
            return Err(CryptoError::SessionPoisoned);
        }
        if self.step != ServerHandshakeStep::Complete {
            return Err(CryptoError::InvalidStateProgression);
        }
        let core = transport_from_handshake(self.inner)?;
        Ok(ServerTransport {
            core: Arc::new(core),
        })
    }
}
