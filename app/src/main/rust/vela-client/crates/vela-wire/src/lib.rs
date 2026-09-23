#![forbid(unsafe_code)]
//! Exclusive ownership boundary for Vela wire encoding and decoding.
pub mod cursor;
pub mod endpoint;
pub mod error;
pub mod frame;
pub mod outer;

pub use endpoint::*;
pub use error::*;
pub use frame::*;
pub use outer::*;

#[derive(Debug, Default, Clone, Copy)]
pub struct WireBoundary;
