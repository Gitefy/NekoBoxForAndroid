#![forbid(unsafe_code)]
//! Transport boundary: authenticated record exchange and the session frame
//! pump. The `tcp` module provides the RI-6 TCP1 socket runtime.

pub mod driver;
pub mod error;
pub mod handshake;
pub mod record;
pub mod replay;
pub mod tcp;

/// Marker for the exclusive Vela transport ownership boundary.
#[derive(Debug, Default, Clone, Copy)]
pub struct TransportBoundary;

pub use driver::{PRODUCTION_SESSION_LIFETIME, PumpReport, RekeyPolicy, SessionDriver};
pub use error::TransportError;
pub use record::RecordChannel;
pub use replay::{DEFAULT_REPLAY_CAPACITY, DEFAULT_REPLAY_TTL, HandshakeReplayGuard};
pub use vela_wire::WireError;
