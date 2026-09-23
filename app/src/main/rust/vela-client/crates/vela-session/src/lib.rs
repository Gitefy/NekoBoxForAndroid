#![forbid(unsafe_code)]
//! Exclusive ownership boundary for Vela protocol state transitions.
//!
//! `vela-session` owns session lifecycle, session control frames, connection
//! and stream flow-control accounting, identifier allocation, and stream
//! multiplexing state. It performs NO cryptography (owned by `vela-crypto`),
//! NO frame codec work (owned by `vela-wire`), and NO socket I/O (owned by
//! `vela-transport`). It is synchronous, deterministic, and I/O-free so that
//! its state machines are exhaustively testable.
//!
//! Fail-closed invariant (spec §16): every protocol violation is terminal for
//! the connection. There is no fallback, no degraded mode, and no attempt to
//! guess the peer's intent.

pub mod connection;
pub mod control;
pub mod credit;
pub mod datagram;
pub mod dns;
pub mod error;
pub mod ids;
pub mod stream;

/// Marker for the exclusive Vela session ownership boundary.
#[derive(Debug, Default, Clone, Copy)]
pub struct SessionBoundary;

pub use connection::{ConnectionState, Role, Session, SessionConfig};
pub use control::SessionEffects;
pub use error::SessionError;
