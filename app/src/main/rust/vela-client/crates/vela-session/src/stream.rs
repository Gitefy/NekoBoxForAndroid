//! Vela stream lifecycle, multiplexing and per-stream credit (spec §10).
//!
//! Invariants:
//! - `StreamID` is `u32`; `0` is reserved; client IDs are odd, server IDs are
//!   even; IDs strictly increase per initiator and are never reused.
//! - The Vela/1 production profile disables server-initiated `STREAM_OPEN`.
//! - `STREAM_DATA` is legal only after a successful `OPEN_RESULT`.
//! - `STREAM_FIN` is a directional half-close; no further `STREAM_DATA` may
//!   follow from that sender.
//! - `STREAM_RESET` is terminal for both directions.
//! - `STREAM_MAX_DATA` is an absolute cumulative directional credit that is
//!   monotonically non-decreasing.
//! - A failed `OPEN_RESULT` MUST carry zero `InitialMaxData`.

use std::collections::HashMap;

use vela_proto::{OpenResultStatus, StreamId, StreamResetCode};

use crate::connection::Role;
use crate::credit::{ReceiveWindow, SendWindow};
use crate::error::SessionError;
use crate::ids::StreamIdAllocator;

/// Maximum number of closed-stream crossing tombstones retained per session.
///
/// A tombstone exists only while a racing peer frame (`OPEN_RESULT` or
/// in-flight `DATA` crossing our local `RESET`) may still legally arrive.
/// Anything beyond this cap is released instead of retained; a late racing
/// frame then fails closed as `unknown stream id` (terminal for the session,
/// never silent). Worst-case bound: `cap × sizeof(Stream)` ≈ 1024 × ~100 B.
pub const MAX_RETAINED_CLOSED: usize = 1_024;

/// Direction of a half-close or credit window.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamDirection {
    /// The local endpoint -> peer direction.
    Local,
    /// The peer -> local endpoint direction.
    Remote,
}

/// Internal close outcome for [`StreamTable::fin`]: released entries free
/// their slot at once (bounded history), with or without an active counter.
enum FinOutcome {
    ReleaseDecrement,
    ReleaseNoDecrement,
    Keep,
}

/// Lifecycle state of a single stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamState {
    /// `STREAM_OPEN` sent or received; awaiting `STREAM_OPEN_RESULT`.
    Opening,
    /// Both directions may carry data.
    Active,
    /// The local endpoint sent `STREAM_FIN`.
    HalfClosedLocal,
    /// The peer sent `STREAM_FIN`.
    HalfClosedRemote,
    /// Terminal: closed by both sides, by reset, or by a failed open.
    Closed,
}

impl StreamState {
    /// Returns `true` when the local endpoint may still send `STREAM_DATA`.
    #[must_use]
    pub const fn can_send(self) -> bool {
        matches!(self, Self::Active | Self::HalfClosedRemote)
    }

    /// Returns `true` when the peer may still send `STREAM_DATA`.
    #[must_use]
    pub const fn can_receive(self) -> bool {
        matches!(self, Self::Active | Self::HalfClosedLocal)
    }

    /// Returns `true` for the terminal state.
    #[must_use]
    pub const fn is_closed(self) -> bool {
        matches!(self, Self::Closed)
    }
}

/// A single Vela stream: state plus directional credit windows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Stream {
    state: StreamState,
    locally_reset: bool,
    awaiting_open_result: bool,
    reset_receive_open: bool,
    send: SendWindow,
    recv: ReceiveWindow,
}

/// Table of all streams known to a session endpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamTable {
    role: Role,
    max_concurrent: u32,
    active_streams: u32,
    ids: StreamIdAllocator,
    streams: HashMap<u32, Stream>,
}

impl StreamTable {
    /// Creates an empty stream table for the given role and ceiling.
    #[must_use]
    pub fn new(role: Role, max_concurrent: u32) -> Self {
        Self {
            role,
            max_concurrent,
            active_streams: 0,
            ids: StreamIdAllocator::new(role),
            streams: HashMap::new(),
        }
    }

    /// Returns the largest known stream identifier, or `0` if none exist.
    #[must_use]
    pub fn max_id(&self) -> u32 {
        self.streams.keys().copied().max().unwrap_or(0)
    }

    /// Returns the next locally allocated stream identifier without consuming it.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] once the identifier space is exhausted.
    pub fn peek_next_id(&self) -> Result<u32, SessionError> {
        self.ids.peek()
    }

    /// Returns the number of streams that are not yet closed.
    #[must_use]
    pub fn open_count(&self) -> u32 {
        self.active_streams
    }

    /// Returns the total number of retained table entries, including active
    /// streams and bounded crossing tombstones for locally reset streams.
    ///
    /// Invariant: `retained_count() <= open_count() + MAX_RETAINED_CLOSED`.
    /// Fully closed streams with no legal racing frame outstanding are
    /// released immediately, so a peer cannot grow this table without bound.
    #[must_use]
    pub fn retained_count(&self) -> usize {
        self.streams.len()
    }

    /// Returns the state of a known stream, or `None` if unknown or already
    /// released after a fully closed lifecycle (see [`Self::retained_count`]).
    #[must_use]
    pub fn state(&self, id: StreamId) -> Option<StreamState> {
        self.streams.get(&id.get()).map(|s| s.state)
    }

    /// Opens a locally initiated stream, emitting a new `StreamID`.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for server-initiated opens
    /// (production profile) and [`SessionError::ResourceLimit`] when the
    /// concurrent-stream ceiling is reached.
    pub fn open(&mut self, initial_max_data: u64) -> Result<StreamId, SessionError> {
        if self.role == Role::Server {
            return Err(SessionError::ProtocolViolation(
                "server-initiated stream open is disabled in the production profile",
            ));
        }
        if self.open_count() >= self.max_concurrent {
            return Err(SessionError::ResourceLimit("too many concurrent streams"));
        }
        let id = self.ids.allocate()?;
        let mut recv = ReceiveWindow::new();
        recv.advertise(initial_max_data)?;
        self.streams.insert(
            id.get(),
            Stream {
                state: StreamState::Opening,
                locally_reset: false,
                awaiting_open_result: true,
                reset_receive_open: false,
                send: SendWindow::new(),
                recv,
            },
        );
        self.active_streams =
            self.active_streams
                .checked_add(1)
                .ok_or(SessionError::ResourceLimit(
                    "active stream counter overflow",
                ))?;
        Ok(id)
    }

    /// Registers a peer-initiated `STREAM_OPEN` and returns its validated ID.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] on parity, monotonicity, or reuse
    /// violations, and [`SessionError::ResourceLimit`] at the ceiling.
    pub fn on_peer_open(
        &mut self,
        id: StreamId,
        peer_initial_max_data: u64,
    ) -> Result<StreamId, SessionError> {
        self.ids.validate_peer(id)?;
        if self.open_count() >= self.max_concurrent {
            return Err(SessionError::ResourceLimit("too many concurrent streams"));
        }
        let mut send = SendWindow::new();
        send.raise(peer_initial_max_data)?;
        self.streams.insert(
            id.get(),
            Stream {
                state: StreamState::Opening,
                locally_reset: false,
                awaiting_open_result: true,
                reset_receive_open: false,
                send,
                recv: ReceiveWindow::new(),
            },
        );
        self.active_streams =
            self.active_streams
                .checked_add(1)
                .ok_or(SessionError::ResourceLimit(
                    "active stream counter overflow",
                ))?;
        Ok(id)
    }

    /// Processes `STREAM_OPEN_RESULT` for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when a failed result carries
    /// non-zero `InitialMaxData`, or when the stream is unknown.
    pub fn on_open_result(
        &mut self,
        id: StreamId,
        status: OpenResultStatus,
        initial_max_data: u64,
    ) -> Result<(), SessionError> {
        let key = id.get();
        if !status.is_success() {
            if initial_max_data != 0 {
                return Err(SessionError::ProtocolViolation(
                    "failed open result must carry zero initial max data",
                ));
            }
            let was_counted = self
                .streams
                .get(&key)
                .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
            let was_counted = !was_counted.state.is_closed();
            if was_counted {
                self.active_streams =
                    self.active_streams
                        .checked_sub(1)
                        .ok_or(SessionError::ProtocolViolation(
                            "active stream counter underflow",
                        ))?;
            }
            // A failed open ends the stream with no legal racing frame:
            // release the entry instead of retaining a tombstone.
            self.streams.remove(&key);
            return Ok(());
        }
        let stream = self
            .streams
            .get_mut(&key)
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        if !stream.awaiting_open_result {
            return Err(SessionError::ProtocolViolation(
                "unexpected stream open result",
            ));
        }
        stream.send.raise(initial_max_data)?;
        if stream.locally_reset {
            stream.reset_receive_open = true;
        } else {
            stream.state = StreamState::Active;
        }
        stream.awaiting_open_result = false;
        Ok(())
    }

    /// Validates that `len` bytes may be sent, without mutating state.
    ///
    /// # Errors
    /// Mirrors [`StreamTable::send_data`].
    pub fn check_send(&self, id: StreamId, len: u64) -> Result<(), SessionError> {
        let stream = self
            .streams
            .get(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        if !stream.state.can_send() {
            return Err(SessionError::ProtocolViolation(
                "stream data is not allowed in this state",
            ));
        }
        stream.send.check_consume(len)
    }

    /// Activates a peer-initiated stream as the responder, granting the local
    /// receive credit advertised via `STREAM_OPEN_RESULT` (spec §10).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the stream is unknown or not in
    /// the [`StreamState::Opening`] state.
    pub fn activate_as_responder(
        &mut self,
        id: StreamId,
        receive_credit: u64,
    ) -> Result<(), SessionError> {
        let stream = self
            .streams
            .get_mut(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        if stream.state != StreamState::Opening {
            return Err(SessionError::ProtocolViolation("stream is not opening"));
        }
        stream.recv.advertise(receive_credit)?;
        stream.state = StreamState::Active;
        stream.awaiting_open_result = false;
        Ok(())
    }

    /// Accounts for locally sent `STREAM_DATA` payload bytes.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the stream is not sendable or
    /// credit is insufficient.
    pub fn send_data(&mut self, id: StreamId, len: u64) -> Result<(), SessionError> {
        let stream = self
            .streams
            .get_mut(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        if !stream.state.can_send() {
            return Err(SessionError::ProtocolViolation(
                "stream data is not allowed in this state",
            ));
        }
        stream.send.consume(len)
    }

    /// Accounts for received `STREAM_DATA` payload bytes.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the stream cannot receive or
    /// the peer exceeds advertised credit.
    pub fn receive_data(&mut self, id: StreamId, len: u64) -> Result<(), SessionError> {
        let stream = self
            .streams
            .get_mut(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        if !stream.state.can_receive() && !stream.reset_receive_open {
            return Err(SessionError::ProtocolViolation(
                "stream data is not allowed in this state",
            ));
        }
        stream.recv.record(len)
    }

    /// Applies a directional `STREAM_FIN`.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams or a repeated
    /// FIN from the same direction.
    pub fn fin(&mut self, id: StreamId, direction: StreamDirection) -> Result<(), SessionError> {
        let key = id.get();
        let outcome = {
            let stream = self
                .streams
                .get_mut(&key)
                .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
            if stream.locally_reset && direction == StreamDirection::Remote {
                stream.reset_receive_open = false;
                // Peer FIN ends any in-flight window: no racing frame remains
                // legal, so the entry is always released here.
                if stream.state.is_closed() {
                    FinOutcome::ReleaseNoDecrement
                } else {
                    FinOutcome::ReleaseDecrement
                }
            } else {
                let was_counted = !stream.state.is_closed();
                stream.state = match (stream.state, direction) {
                    (StreamState::Active, StreamDirection::Local) => StreamState::HalfClosedLocal,
                    (StreamState::Active, StreamDirection::Remote) => StreamState::HalfClosedRemote,
                    (StreamState::HalfClosedLocal, StreamDirection::Remote)
                    | (StreamState::HalfClosedRemote, StreamDirection::Local) => {
                        StreamState::Closed
                    }
                    _ => {
                        return Err(SessionError::ProtocolViolation(
                            "stream fin is not allowed in this state",
                        ));
                    }
                };
                if stream.state.is_closed() {
                    if was_counted {
                        FinOutcome::ReleaseDecrement
                    } else {
                        FinOutcome::ReleaseNoDecrement
                    }
                } else {
                    FinOutcome::Keep
                }
            }
        };
        match outcome {
            FinOutcome::Keep => Ok(()),
            FinOutcome::ReleaseNoDecrement => {
                self.streams.remove(&key);
                Ok(())
            }
            FinOutcome::ReleaseDecrement => {
                self.active_streams =
                    self.active_streams
                        .checked_sub(1)
                        .ok_or(SessionError::ProtocolViolation(
                            "active stream counter underflow",
                        ))?;
                self.streams.remove(&key);
                Ok(())
            }
        }
    }

    /// Marks a locally reset stream while retaining its receive window for
    /// authenticated data already in flight when RESET crossed the network.
    ///
    /// The entry is retained only while a racing peer frame may still legally
    /// arrive (a crossing `OPEN_RESULT` or in-flight `DATA`); otherwise it is
    /// released immediately. Retained tombstones are capped by
    /// [`MAX_RETAINED_CLOSED`]; beyond the cap the entry is released and any
    /// late racing frame fails closed as `unknown stream id`.
    /// # Errors
    /// Returns an error for an unknown stream.
    pub fn reset_local(
        &mut self,
        id: StreamId,
        _code: StreamResetCode,
    ) -> Result<(), SessionError> {
        let key = id.get();
        let (receive_open, awaiting) = {
            let stream = self
                .streams
                .get(&key)
                .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
            (
                stream.state.can_receive() || stream.reset_receive_open,
                stream.awaiting_open_result,
            )
        };
        {
            let stream = self
                .streams
                .get_mut(&key)
                .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
            if !stream.state.is_closed() {
                self.active_streams =
                    self.active_streams
                        .checked_sub(1)
                        .ok_or(SessionError::ProtocolViolation(
                            "active stream counter underflow",
                        ))?;
            }
            stream.state = StreamState::Closed;
            stream.locally_reset = true;
            stream.awaiting_open_result = awaiting;
            stream.reset_receive_open = receive_open;
        }
        let retained = self
            .streams
            .len()
            .saturating_sub(self.active_streams as usize);
        if !(awaiting || receive_open) || retained > MAX_RETAINED_CLOSED {
            self.streams.remove(&key);
        }
        Ok(())
    }

    /// Whether late inbound payload must be discarded after a local reset.
    #[must_use]
    pub fn is_locally_reset(&self, id: StreamId) -> bool {
        self.streams
            .get(&id.get())
            .is_some_and(|stream| stream.locally_reset)
    }

    /// Applies `STREAM_RESET`: terminal for both directions.
    ///
    /// The entry is always released: a peer `RESET` ends any in-flight
    /// window, and duplicate post-close frames fail closed as unknown.
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn reset(&mut self, id: StreamId, _code: StreamResetCode) -> Result<(), SessionError> {
        let key = id.get();
        let was_counted = self
            .streams
            .get(&key)
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        let was_counted = !was_counted.state.is_closed();
        if was_counted {
            self.active_streams =
                self.active_streams
                    .checked_sub(1)
                    .ok_or(SessionError::ProtocolViolation(
                        "active stream counter underflow",
                    ))?;
        }
        self.streams.remove(&key);
        Ok(())
    }

    /// Processes `STREAM_MAX_DATA` granting additional send credit.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the value decreases or the
    /// stream is unknown.
    pub fn raise_send_credit(&mut self, id: StreamId, max_data: u64) -> Result<(), SessionError> {
        let stream = self
            .streams
            .get_mut(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        stream.send.raise(max_data)
    }

    /// Advertises additional receive credit to the peer.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the value decreases or the
    /// stream is unknown.
    pub fn raise_receive_credit(
        &mut self,
        id: StreamId,
        max_data: u64,
    ) -> Result<(), SessionError> {
        let stream = self
            .streams
            .get_mut(&id.get())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))?;
        stream.recv.advertise(max_data)?;
        Ok(())
    }

    /// Returns the remaining send credit for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn send_credit(&self, id: StreamId) -> Result<u64, SessionError> {
        self.streams
            .get(&id.get())
            .map(|s| s.send.remaining())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))
    }

    /// Returns the remaining receive credit for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn receive_credit(&self, id: StreamId) -> Result<u64, SessionError> {
        self.streams
            .get(&id.get())
            .map(|s| s.recv.remaining())
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))
    }

    /// Returns the (received, advertised) cumulative bytes for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn receive_window(&self, id: StreamId) -> Result<(u64, u64), SessionError> {
        self.streams
            .get(&id.get())
            .map(|s| (s.recv.received(), s.recv.advertised()))
            .ok_or(SessionError::ProtocolViolation("unknown stream id"))
    }
}
