//! Vela flow-control credit accounting (spec §9).
//!
//! Connection-level and stream-level credit are **absolute cumulative** limits
//! on `STREAM_DATA` payload bytes. Normative properties:
//! - `peer_connection_max_data` is exactly `0` before the first
//!   `CONNECTION_MAX_DATA` is received (A-002).
//! - `MaximumData` is monotonically non-decreasing: equal values are ignored,
//!   smaller values are a protocol error.
//! - No endpoint may transmit `STREAM_DATA` while peer connection credit is zero.
//! - Only `STREAM_DATA` payload bytes consume connection credit; control, open,
//!   result, DNS, and datagram frames consume none.
//! - All arithmetic is checked: overflow is a protocol error, never a wrap.

use crate::error::SessionError;

/// Outgoing send window: credit granted by the peer for local `STREAM_DATA`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SendWindow {
    limit: u64,
    consumed: u64,
}

impl SendWindow {
    /// Creates a window with zero credit (spec §9, A-002).
    #[must_use]
    pub const fn new() -> Self {
        Self {
            limit: 0,
            consumed: 0,
        }
    }

    /// Returns the credit still available for `STREAM_DATA`.
    #[must_use]
    pub const fn remaining(&self) -> u64 {
        self.limit - self.consumed
    }

    /// Returns the absolute cumulative limit granted by the peer.
    #[must_use]
    pub const fn limit(&self) -> u64 {
        self.limit
    }

    /// Processes a peer `CONNECTION_MAX_DATA` / `STREAM_MAX_DATA` value.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the value decreases.
    pub fn raise(&mut self, new_limit: u64) -> Result<(), SessionError> {
        if new_limit < self.limit {
            return Err(SessionError::ProtocolViolation(
                "flow-control limit decreased",
            ));
        }
        self.limit = new_limit;
        Ok(())
    }

    /// Validates that `n` bytes may be consumed, without mutating state.
    ///
    /// # Errors
    /// Mirrors [`SendWindow::consume`].
    pub fn check_consume(&self, n: u64) -> Result<(), SessionError> {
        let consumed = self
            .consumed
            .checked_add(n)
            .ok_or(SessionError::ProtocolViolation(
                "flow-control counter overflow",
            ))?;
        if consumed > self.limit {
            return Err(SessionError::Backpressure);
        }
        Ok(())
    }

    /// Consumes credit for `n` bytes of `STREAM_DATA` payload.
    ///
    /// # Errors
    /// [`SessionError::Backpressure`] when credit is insufficient (non-terminal),
    /// [`SessionError::ProtocolViolation`] when the cumulative counter would
    /// overflow.
    pub fn consume(&mut self, n: u64) -> Result<(), SessionError> {
        let consumed = self
            .consumed
            .checked_add(n)
            .ok_or(SessionError::ProtocolViolation(
                "flow-control counter overflow",
            ))?;
        if consumed > self.limit {
            return Err(SessionError::Backpressure);
        }
        self.consumed = consumed;
        Ok(())
    }
}

impl Default for SendWindow {
    fn default() -> Self {
        Self::new()
    }
}

/// Incoming receive window: credit advertised to the peer for its `STREAM_DATA`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReceiveWindow {
    advertised: u64,
    received: u64,
}

impl ReceiveWindow {
    /// Creates a window that has advertised nothing.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            advertised: 0,
            received: 0,
        }
    }

    /// Returns the absolute cumulative limit advertised to the peer.
    #[must_use]
    pub const fn advertised(&self) -> u64 {
        self.advertised
    }

    /// Returns the cumulative number of payload bytes received.
    #[must_use]
    pub const fn received(&self) -> u64 {
        self.received
    }

    /// Returns the credit the peer may still use.
    #[must_use]
    pub const fn remaining(&self) -> u64 {
        self.advertised - self.received
    }

    /// Advertises a new absolute cumulative limit.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the value decreases.
    pub fn advertise(&mut self, new_limit: u64) -> Result<(), SessionError> {
        if new_limit < self.advertised {
            return Err(SessionError::ProtocolViolation(
                "flow-control limit decreased",
            ));
        }
        self.advertised = new_limit;
        Ok(())
    }

    /// Records receipt of `n` payload bytes.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the peer exceeds the advertised
    /// credit or the cumulative counter would overflow.
    pub fn record(&mut self, n: u64) -> Result<(), SessionError> {
        let received = self
            .received
            .checked_add(n)
            .ok_or(SessionError::ProtocolViolation(
                "flow-control counter overflow",
            ))?;
        if received > self.advertised {
            return Err(SessionError::ProtocolViolation(
                "peer exceeded flow-control credit",
            ));
        }
        self.received = received;
        Ok(())
    }

    /// Test-only hook: forces the cumulative counter near overflow.
    #[cfg(test)]
    pub(crate) fn force_received_for_testing(&mut self, received: u64) {
        self.received = received;
    }
}

impl Default for ReceiveWindow {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Cumulative counter overflow must be rejected rather than wrapped.
    #[test]
    fn receive_window_counter_overflow_is_rejected() {
        let mut window = ReceiveWindow::new();
        window.advertise(u64::MAX).expect("advertise max");
        window.record(1).expect("record one byte");
        window.force_received_for_testing(u64::MAX);
        assert_eq!(
            window.record(1),
            Err(SessionError::ProtocolViolation(
                "flow-control counter overflow"
            ))
        );
    }

    /// Send-side consumption overflow is rejected rather than wrapped.
    #[test]
    fn send_window_counter_overflow_is_rejected() {
        let mut window = SendWindow::new();
        window.raise(u64::MAX).expect("raise");
        window.consume(u64::MAX).expect("consume all");
        // The cumulative counter would overflow: rejected, never wrapped.
        assert_eq!(
            window.consume(1),
            Err(SessionError::ProtocolViolation(
                "flow-control counter overflow"
            ))
        );
    }
}
