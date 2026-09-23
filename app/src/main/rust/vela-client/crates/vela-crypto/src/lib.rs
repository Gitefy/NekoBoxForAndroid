#![forbid(unsafe_code)]
//! Exclusive ownership boundary for Vela cryptographic integration.
//!
//! `vela-crypto` is the **only** crate in the workspace permitted to depend on
//! Noise libraries or low-level cryptographic primitives. It owns Noise
//! handshake execution, ephemeral key handling, directional AEAD
//! encryption/decryption, channel binding extraction, and Noise Rekey
//! operations. It never parses Vela frames, never touches sockets, and never
//! manages session lifecycle.
//!
//! Crypto profile (frozen): VCP-1 (XK over X25519 with ChaCha20-Poly1305 and
//! SHA-256), transport profile TCP1.

pub mod error;
pub mod handshake;
pub mod keys;
pub mod transport;

/// Marker for the exclusive Vela cryptographic ownership boundary.
#[derive(Debug, Default, Clone, Copy)]
pub struct CryptoBoundary;

pub use error::CryptoError;
pub use handshake::{ClientHandshake, ServerHandshake};
pub use keys::{
    ClientStaticPrivateKey, ClientStaticPublicKey, HandshakeHash, ServerStaticPrivateKey,
    ServerStaticPublicKey,
};
pub use transport::{
    ClientReceiver, ClientSender, ClientTransport, ServerReceiver, ServerSender, ServerTransport,
    SessionLivenessGuard,
};
