//! Session control frames and the unified session facade (spec §8, §9).
//!
//! `vela-session` performs no I/O: every transition returns a
//! [`SessionEffects`] value describing what the caller (`vela-transport`,
//! RI-4) must do — send frames, rekey a direction, or terminate.
//!
//! Cryptography is never invoked here: [`SessionEffects::rekey_send`] and
//! [`SessionEffects::rekey_recv`] are signals for `vela-crypto` (RI-2).

use vela_proto::{
    ContextId, DatagramCloseReason, DnsRequestId, Endpoint, Frame, GoAwayReason, OpenResultStatus,
    SessionRejectReason, StreamId, StreamResetCode,
};

use crate::connection::{ConnectionState, Role, Session, SessionConfig};
use crate::credit::{ReceiveWindow, SendWindow};
use crate::datagram::DatagramTable;
use crate::dns::DnsTable;
use crate::error::SessionError;
use crate::stream::{StreamDirection, StreamTable};

/// Effects produced by a session transition, to be executed by the caller.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct SessionEffects {
    /// Frames that must be written to the transport, in order.
    pub frames: Vec<Frame>,
    /// `true` when the caller must rekey the send direction after writing
    /// `KEY_UPDATE` under the old key.
    pub rekey_send: bool,
    /// `true` when the caller must rekey the receive direction after
    /// processing `KEY_UPDATE` under the old key.
    pub rekey_recv: bool,
    /// `true` when the connection has terminated and must be closed.
    pub terminate: bool,
    /// Stream payloads successfully authenticated and accepted for delivery.
    pub delivered: Vec<(StreamId, Vec<u8>)>,
    /// Receive bytes discarded after local reset; release connection credit.
    pub discarded_stream_bytes: u64,
    /// Datagram payloads accepted for delivery on ACTIVE contexts.
    pub delivered_datagrams: Vec<(ContextId, Vec<u8>)>,
    /// DNS responses matched to outstanding requests.
    pub dns_responses: Vec<(DnsRequestId, Vec<u8>)>,
    /// Inbound DNS queries surfaced to the DNS subsystem (server side).
    pub dns_queries: Vec<(DnsRequestId, Vec<u8>)>,
    /// Inbound stream open requests from the peer awaiting a policy decision.
    pub inbound_stream_opens: Vec<(StreamId, Endpoint)>,
    /// Inbound datagram context open requests from the peer awaiting a policy
    /// decision.
    pub inbound_datagram_opens: Vec<(ContextId, Endpoint)>,
    /// Inbound stream FIN frames received from the peer.
    pub stream_fins: Vec<StreamId>,
    /// Inbound stream RESET frames received from the peer.
    pub stream_resets: Vec<(StreamId, StreamResetCode)>,
    /// Authenticated result of an outbound stream open.
    pub stream_open_results: Vec<(StreamId, OpenResultStatus)>,
    /// Authenticated result of an outbound datagram open.
    pub datagram_open_results: Vec<(ContextId, OpenResultStatus)>,
    /// Datagram contexts closed by the peer.
    pub datagram_closes: Vec<(ContextId, DatagramCloseReason)>,
}

impl SessionEffects {
    /// Returns empty effects (no action required).
    #[must_use]
    pub fn none() -> Self {
        Self::default()
    }

    fn send(frame: Frame) -> Self {
        Self {
            frames: vec![frame],
            ..Self::default()
        }
    }

    fn terminated() -> Self {
        Self {
            terminate: true,
            ..Self::default()
        }
    }
}

/// Runtime state owned by a session endpoint beyond its lifecycle state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SessionRuntime {
    pub(crate) streams: StreamTable,
    pub(crate) datagrams: DatagramTable,
    pub(crate) dns: DnsTable,
    pub(crate) conn_send: SendWindow,
    pub(crate) conn_recv: ReceiveWindow,
    pub(crate) pending_pings: Vec<u64>,
    pub(crate) goaway_ceiling: Option<u32>,
}

impl SessionRuntime {
    pub(crate) fn new(config: &SessionConfig) -> Self {
        let mut conn_recv = ReceiveWindow::new();
        // Advertising receive credit is validated and cannot fail at zero.
        let _ = conn_recv.advertise(config.initial_receive_credit);
        Self {
            streams: StreamTable::new(config.role, config.max_concurrent_streams),
            datagrams: DatagramTable::new(config.role, config.max_concurrent_streams),
            dns: DnsTable::new(),
            conn_send: SendWindow::new(),
            conn_recv,
            pending_pings: Vec::new(),
            goaway_ceiling: None,
        }
    }
}

impl Session {
    /// Server: authorize the session.
    ///
    /// The server's first transport frames are `SESSION_ACCEPT` followed by
    /// `CONNECTION_MAX_DATA` (spec §8, §9).
    ///
    /// # Errors
    /// Role, state, and terminal-state errors as documented on the underlying
    /// state machine.
    pub fn accept_with_effects(&mut self) -> Result<SessionEffects, SessionError> {
        self.accept()?;
        Ok(SessionEffects {
            frames: vec![
                Frame::SessionAccept,
                Frame::ConnectionMaxData {
                    maximum_data: self.runtime.conn_recv.advertised(),
                },
            ],
            ..SessionEffects::default()
        })
    }

    /// Server: reject the session and terminate.
    ///
    /// # Errors
    /// Role and terminal-state errors.
    pub fn reject_with_effects(
        &mut self,
        reason: SessionRejectReason,
    ) -> Result<SessionEffects, SessionError> {
        self.reject(reason)?;
        Ok(SessionEffects {
            frames: vec![Frame::SessionReject { reason }],
            terminate: true,
            ..SessionEffects::default()
        })
    }

    /// Locally initiated graceful shutdown: `GOAWAY` then drain (spec §8, §14).
    ///
    /// Idle sessions (no open streams or datagram contexts) close immediately
    /// after writing `GOAWAY`.
    ///
    /// # Errors
    /// Role/state errors from [`Session::begin_drain`].
    pub fn goaway_with_effects(
        &mut self,
        reason: GoAwayReason,
    ) -> Result<SessionEffects, SessionError> {
        self.begin_drain(reason)?;
        let last_stream_id = self.runtime.streams.max_id();
        self.runtime.goaway_ceiling = Some(last_stream_id);
        let terminate = self.complete_drain_if_idle()?;
        Ok(SessionEffects {
            frames: vec![Frame::GoAway {
                last_stream_id,
                reason,
            }],
            terminate,
            ..SessionEffects::default()
        })
    }

    /// Sends a `PING` with the given opaque value.
    ///
    /// # Errors
    /// [`SessionError::Closed`] on a terminated session.
    pub fn send_ping(&mut self, opaque: u64) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.pending_pings.push(opaque);
        Ok(SessionEffects::send(Frame::Ping { opaque }))
    }

    /// Returns whether any `PING` frame is currently outstanding awaiting `PONG`.
    #[must_use]
    pub fn has_pending_pings(&self) -> bool {
        !self.runtime.pending_pings.is_empty()
    }

    /// Initiates a rekey: `KEY_UPDATE` is the last frame under the old key and
    /// the send direction is rekeyed immediately after it is written (§8).
    ///
    /// # Errors
    /// [`SessionError::Closed`] on a terminated session.
    pub fn initiate_key_update(&mut self) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        Ok(SessionEffects {
            frames: vec![Frame::KeyUpdate],
            rekey_send: true,
            ..SessionEffects::default()
        })
    }

    /// Opens a stream toward the given endpoint, emitting `STREAM_OPEN`.
    ///
    /// # Errors
    /// State, ceiling, identifier, and role errors.
    pub fn open_stream(
        &mut self,
        endpoint: Endpoint,
        initial_max_data: u64,
    ) -> Result<(StreamId, SessionEffects), SessionError> {
        self.require_live()?;
        self.forbid_stream_above_goaway()?;
        let id = self.runtime.streams.open(initial_max_data)?;
        let effects = SessionEffects::send(Frame::StreamOpen {
            stream_id: id,
            endpoint,
            initial_max_data,
        });
        Ok((id, effects))
    }

    /// Server: accepts a peer-initiated stream by emitting
    /// `STREAM_OPEN_RESULT(OK)` and granting local receive credit (spec §10).
    ///
    /// # Errors
    /// State and stream-state errors.
    pub fn accept_stream(
        &mut self,
        id: StreamId,
        initial_max_data: u64,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime
            .streams
            .activate_as_responder(id, initial_max_data)?;
        let frame = Frame::stream_open_result(id, OpenResultStatus::Ok, initial_max_data)
            .map_err(|_| SessionError::ProtocolViolation("invalid open result frame"))?;
        Ok(SessionEffects::send(frame))
    }

    /// Server: refuses a peer-initiated stream. The stream closes and the
    /// result MUST carry zero `InitialMaxData` (spec §10).
    ///
    /// # Errors
    /// State and stream-state errors.
    pub fn reject_stream(
        &mut self,
        id: StreamId,
        status: OpenResultStatus,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.streams.on_open_result(id, status, 0)?;
        let frame = Frame::stream_open_result(id, status, 0)
            .map_err(|_| SessionError::ProtocolViolation("invalid open result frame"))?;
        self.send_after_progress(frame)
    }

    /// Client: sends a `DNS_QUERY`, allocating a new outstanding request ID.
    ///
    /// # Errors
    /// State and identifier-space errors.
    pub fn send_dns_query(
        &mut self,
        message: Vec<u8>,
    ) -> Result<(DnsRequestId, SessionEffects), SessionError> {
        self.require_live()?;
        let request_id = self.runtime.dns.query()?;
        let frame = Frame::dns_query(request_id, message)
            .map_err(|_| SessionError::ProtocolViolation("invalid dns query frame"))?;
        Ok((request_id, SessionEffects::send(frame)))
    }

    /// Client: cancels an outstanding `DNS_QUERY` (emits `DNS_CANCEL`).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the request is not outstanding.
    pub fn cancel_dns_query(
        &mut self,
        request_id: DnsRequestId,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.dns.cancel(request_id)?;
        Ok(SessionEffects::send(Frame::DnsCancel { request_id }))
    }

    /// Server: answers an inbound `DNS_QUERY` (emits `DNS_RESPONSE`).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when no matching query is
    /// outstanding.
    pub fn send_dns_response(
        &mut self,
        request_id: DnsRequestId,
        message: Vec<u8>,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.dns.respond(request_id)?;
        let frame = Frame::dns_response(request_id, message)
            .map_err(|_| SessionError::ProtocolViolation("invalid dns response frame"))?;
        Ok(SessionEffects::send(frame))
    }

    /// Client: opens a datagram context, emitting `DATAGRAM_OPEN`.
    ///
    /// # Errors
    /// State, ceiling, and identifier-space errors.
    pub fn open_datagram(
        &mut self,
        endpoint: Endpoint,
    ) -> Result<(ContextId, SessionEffects), SessionError> {
        self.require_live()?;
        if self.runtime.goaway_ceiling.is_some() {
            return Err(SessionError::ProtocolViolation(
                "no new datagram contexts after GOAWAY",
            ));
        }
        let context_id = self.runtime.datagrams.open()?;
        Ok((
            context_id,
            SessionEffects::send(Frame::DatagramOpen {
                context_id,
                endpoint,
            }),
        ))
    }

    /// Either role: answers a peer `DATAGRAM_OPEN` (emits `DATAGRAM_OPEN_RESULT`).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the context is unknown.
    pub fn answer_datagram(
        &mut self,
        context_id: ContextId,
        status: OpenResultStatus,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.datagrams.on_open_result(context_id, status)?;
        Ok(SessionEffects::send(Frame::DatagramOpenResult {
            context_id,
            status,
        }))
    }

    /// Sends `DATAGRAM_DATA` on an ACTIVE context. Datagram payloads do not
    /// consume connection credit (spec §9: only `STREAM_DATA` does).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the context is not ACTIVE or
    /// the payload exceeds the frame bound.
    pub fn send_datagram(
        &mut self,
        context_id: ContextId,
        payload: Vec<u8>,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.datagrams.send_data(context_id, &payload)?;
        let frame = Frame::datagram_data(context_id, payload)
            .map_err(|_| SessionError::ProtocolViolation("invalid datagram data frame"))?;
        Ok(SessionEffects::send(frame))
    }

    /// Closes a datagram context (emits `DATAGRAM_CLOSE`). Terminal.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the context is unknown.
    pub fn close_datagram(
        &mut self,
        context_id: ContextId,
        reason: DatagramCloseReason,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.datagrams.close(context_id, reason)?;
        self.send_after_progress(Frame::DatagramClose { context_id, reason })
    }

    fn on_inbound_dns_query(&mut self, request_id: DnsRequestId) -> Result<(), SessionError> {
        // DNS request IDs are allocated by the client (spec §13). There is NO
        // parity rule for DNS request IDs; only monotonicity and
        // outstanding-uniqueness apply. A client never receives a query.
        if self.config.role == Role::Client {
            return Err(SessionError::ProtocolViolation(
                "dns queries are client-initiated",
            ));
        }
        self.runtime.dns.track_inbound(request_id)
    }

    fn on_inbound_dns_response(&mut self, request_id: DnsRequestId) -> Result<(), SessionError> {
        // Responses flow server -> client against client-allocated IDs.
        if self.config.role == Role::Server {
            return Err(SessionError::ProtocolViolation(
                "dns responses are client-directed",
            ));
        }
        self.runtime.dns.response(request_id)
    }

    /// Grants additional receive credit to the peer for a stream by emitting
    /// `STREAM_MAX_DATA` (spec §10: absolute cumulative, monotonic).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the value decreases or the
    /// stream is unknown.
    pub fn grant_stream_credit(
        &mut self,
        id: StreamId,
        max_data: u64,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.streams.raise_receive_credit(id, max_data)?;
        Ok(SessionEffects::send(Frame::StreamMaxData {
            stream_id: id,
            maximum_data: max_data,
        }))
    }

    /// Grants additional connection receive credit to the peer by emitting
    /// `CONNECTION_MAX_DATA` (spec §9: absolute cumulative, monotonic).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the value decreases.
    pub fn grant_connection_credit(
        &mut self,
        max_data: u64,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.conn_recv.advertise(max_data)?;
        Ok(SessionEffects::send(Frame::ConnectionMaxData {
            maximum_data: max_data,
        }))
    }

    /// Returns the connection-level remaining receive credit.
    #[must_use]
    pub fn conn_receive_credit(&self) -> u64 {
        self.runtime.conn_recv.remaining()
    }

    /// Returns the connection-level remaining send credit.
    #[must_use]
    pub fn conn_send_credit(&self) -> u64 {
        self.runtime.conn_send.remaining()
    }

    /// Returns the connection-level (received, advertised) cumulative bytes.
    #[must_use]
    pub fn conn_receive_window(&self) -> (u64, u64) {
        (
            self.runtime.conn_recv.received(),
            self.runtime.conn_recv.advertised(),
        )
    }

    /// Returns the remaining send credit for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn stream_send_credit(&self, id: StreamId) -> Result<u64, SessionError> {
        self.runtime.streams.send_credit(id)
    }

    /// Returns the remaining receive credit for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn stream_receive_credit(&self, id: StreamId) -> Result<u64, SessionError> {
        self.runtime.streams.receive_credit(id)
    }

    /// Returns the (received, advertised) cumulative bytes for a stream.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] for unknown streams.
    pub fn stream_receive_window(&self, id: StreamId) -> Result<(u64, u64), SessionError> {
        self.runtime.streams.receive_window(id)
    }

    /// Sends `STREAM_DATA`, consuming both connection and stream credit.
    ///
    /// # Errors
    /// State and credit errors; only `STREAM_DATA` payload bytes consume
    /// connection credit (spec §9).
    pub fn send_stream_data(
        &mut self,
        id: StreamId,
        data: Vec<u8>,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        let len = u64::try_from(data.len()).map_err(|_| {
            SessionError::ProtocolViolation("stream data length exceeds credit accounting range")
        })?;
        // Validate both credit windows before mutating either, so a rejected
        // send never leaves the accounting in a partial state.
        self.runtime.streams.check_send(id, len)?;
        self.runtime.conn_send.check_consume(len)?;
        self.runtime.streams.send_data(id, len)?;
        self.runtime.conn_send.consume(len)?;
        let frame = Frame::stream_data(id, data)
            .map_err(|_| SessionError::ProtocolViolation("invalid stream data frame"))?;
        Ok(SessionEffects::send(frame))
    }

    /// Half-closes a stream locally, emitting `STREAM_FIN`.
    ///
    /// # Errors
    /// State and stream-state errors.
    pub fn finish_stream(&mut self, id: StreamId) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.streams.fin(id, StreamDirection::Local)?;
        self.send_after_progress(Frame::StreamFin { stream_id: id })
    }

    /// Cancels both directions and emits `STREAM_RESET`.
    /// # Errors
    /// Returns state, stream or session progression errors.
    pub fn reset_stream(
        &mut self,
        id: StreamId,
        error: StreamResetCode,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        self.runtime.streams.reset_local(id, error)?;
        self.send_after_progress(Frame::StreamReset {
            stream_id: id,
            error,
        })
    }

    /// Processes an authenticated inbound frame.
    ///
    /// # Errors
    /// Any protocol violation is terminal for the connection (fail-closed,
    /// spec §16).
    pub fn handle_frame(&mut self, frame: Frame) -> Result<SessionEffects, SessionError> {
        if self.state().is_terminal() {
            return Err(SessionError::Closed);
        }
        match frame {
            Frame::SessionAccept => self.on_inbound_session_accept(),
            Frame::SessionReject { reason } => self.on_inbound_session_reject(reason),
            Frame::Ping { opaque } => {
                self.require_live()?;
                Ok(SessionEffects::send(Frame::Pong { opaque }))
            }
            Frame::Pong { opaque } => self.on_inbound_pong(opaque),
            Frame::GoAway {
                last_stream_id,
                reason,
            } => self.on_inbound_goaway(last_stream_id, reason),
            Frame::KeyUpdate => {
                self.require_live()?;
                Ok(SessionEffects {
                    rekey_recv: true,
                    ..SessionEffects::default()
                })
            }
            Frame::ConnectionMaxData { maximum_data } => {
                self.require_live()?;
                self.runtime.conn_send.raise(maximum_data)?;
                Ok(SessionEffects::none())
            }
            Frame::StreamOpen {
                stream_id,
                endpoint,
                initial_max_data,
            } => {
                self.require_live()?;
                self.reject_peer_stream_above_goaway(stream_id)?;
                self.runtime
                    .streams
                    .on_peer_open(stream_id, initial_max_data)?;
                Ok(SessionEffects {
                    inbound_stream_opens: vec![(stream_id, endpoint)],
                    ..SessionEffects::none()
                })
            }
            Frame::StreamOpenResult {
                stream_id,
                status,
                initial_max_data,
            } => {
                self.require_live()?;
                self.runtime
                    .streams
                    .on_open_result(stream_id, status, initial_max_data)?;
                let mut effects = self.effects_none_after_progress()?;
                effects.stream_open_results.push((stream_id, status));
                Ok(effects)
            }
            Frame::StreamData { stream_id, data } => self.on_inbound_stream_data(stream_id, data),
            Frame::StreamFin { stream_id } => {
                self.require_live()?;
                self.runtime
                    .streams
                    .fin(stream_id, StreamDirection::Remote)?;
                let mut effects = self.effects_none_after_progress()?;
                effects.stream_fins.push(stream_id);
                Ok(effects)
            }
            Frame::StreamReset { stream_id, error } => {
                self.require_live()?;
                self.runtime.streams.reset(stream_id, error)?;
                let mut effects = self.effects_none_after_progress()?;
                effects.stream_resets.push((stream_id, error));
                Ok(effects)
            }
            Frame::StreamMaxData {
                stream_id,
                maximum_data,
            } => {
                self.require_live()?;
                self.runtime
                    .streams
                    .raise_send_credit(stream_id, maximum_data)?;
                Ok(SessionEffects::none())
            }
            Frame::DatagramOpen { .. }
            | Frame::DatagramOpenResult { .. }
            | Frame::DatagramData { .. }
            | Frame::DatagramClose { .. } => self.handle_datagram_frame(frame),
            Frame::DnsQuery { .. } | Frame::DnsResponse { .. } | Frame::DnsCancel { .. } => {
                self.handle_dns_frame(frame)
            }
            // Optional extensions (0x40..0x7f) may be ignored as whole frames.
            Frame::UnknownOptional { .. } => Ok(SessionEffects::none()),
        }
    }

    fn handle_datagram_frame(&mut self, frame: Frame) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        match frame {
            Frame::DatagramOpen {
                context_id,
                endpoint,
            } => {
                if self.runtime.goaway_ceiling.is_some() {
                    return Err(SessionError::ProtocolViolation(
                        "no new datagram contexts after GOAWAY",
                    ));
                }
                self.runtime.datagrams.on_peer_open(context_id)?;
                Ok(SessionEffects {
                    inbound_datagram_opens: vec![(context_id, endpoint)],
                    ..SessionEffects::none()
                })
            }
            Frame::DatagramOpenResult { context_id, status } => {
                self.runtime.datagrams.on_open_result(context_id, status)?;
                let mut effects = self.effects_none_after_progress()?;
                effects.datagram_open_results.push((context_id, status));
                Ok(effects)
            }
            Frame::DatagramData {
                context_id,
                payload,
            } => {
                self.runtime.datagrams.send_data(context_id, &payload)?;
                Ok(SessionEffects {
                    delivered_datagrams: vec![(context_id, payload)],
                    ..SessionEffects::default()
                })
            }
            Frame::DatagramClose { context_id, reason } => {
                self.runtime.datagrams.close(context_id, reason)?;
                let mut effects = self.effects_none_after_progress()?;
                effects.datagram_closes.push((context_id, reason));
                Ok(effects)
            }
            _ => Err(SessionError::ProtocolViolation(
                "frame type is outside the datagram dispatcher",
            )),
        }
    }

    fn handle_dns_frame(&mut self, frame: Frame) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        match frame {
            Frame::DnsQuery {
                request_id,
                message,
            } => {
                self.on_inbound_dns_query(request_id)?;
                Ok(SessionEffects {
                    dns_queries: vec![(request_id, message)],
                    ..SessionEffects::default()
                })
            }
            Frame::DnsResponse {
                request_id,
                message,
            } => {
                self.on_inbound_dns_response(request_id)?;
                Ok(SessionEffects {
                    dns_responses: vec![(request_id, message)],
                    ..SessionEffects::default()
                })
            }
            Frame::DnsCancel { request_id } => {
                // DNS_CANCEL is client-initiated (spec §13): the server
                // releases its inbound tracking; a client never receives one.
                match self.config.role {
                    Role::Client => {
                        return Err(SessionError::ProtocolViolation(
                            "dns cancels are client-initiated",
                        ));
                    }
                    Role::Server => self.runtime.dns.cancel_inbound(request_id)?,
                }
                Ok(SessionEffects::none())
            }
            _ => Err(SessionError::ProtocolViolation(
                "frame type is outside the DNS dispatcher",
            )),
        }
    }

    fn on_inbound_session_accept(&mut self) -> Result<SessionEffects, SessionError> {
        self.on_session_accept()?;
        // After accepting, the client begins transmission with its own
        // CONNECTION_MAX_DATA (spec §9).
        Ok(SessionEffects {
            frames: vec![Frame::ConnectionMaxData {
                maximum_data: self.runtime.conn_recv.advertised(),
            }],
            ..SessionEffects::default()
        })
    }

    fn on_inbound_session_reject(
        &mut self,
        reason: SessionRejectReason,
    ) -> Result<SessionEffects, SessionError> {
        self.on_session_reject(reason.as_u16())?;
        Ok(SessionEffects::terminated())
    }

    fn on_inbound_pong(&mut self, opaque: u64) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        if let Some(pos) = self.runtime.pending_pings.iter().position(|&p| p == opaque) {
            self.runtime.pending_pings.remove(pos);
            Ok(SessionEffects::none())
        } else {
            Err(SessionError::ProtocolViolation(
                "pong does not match an outstanding ping",
            ))
        }
    }

    fn on_inbound_goaway(
        &mut self,
        last_stream_id: u32,
        reason: GoAwayReason,
    ) -> Result<SessionEffects, SessionError> {
        self.begin_drain(reason)?;
        self.runtime.goaway_ceiling = Some(last_stream_id);
        self.effects_none_after_progress()
    }

    fn on_inbound_stream_data(
        &mut self,
        stream_id: StreamId,
        data: Vec<u8>,
    ) -> Result<SessionEffects, SessionError> {
        self.require_live()?;
        let len = u64::try_from(data.len()).map_err(|_| {
            SessionError::ProtocolViolation("stream data length exceeds credit accounting range")
        })?;
        self.runtime.conn_recv.record(len)?;
        self.runtime.streams.receive_data(stream_id, len)?;
        if self.runtime.streams.is_locally_reset(stream_id) {
            return Ok(SessionEffects {
                discarded_stream_bytes: len,
                ..SessionEffects::default()
            });
        }
        Ok(SessionEffects {
            delivered: vec![(stream_id, data)],
            ..SessionEffects::default()
        })
    }

    fn require_live(&self) -> Result<(), SessionError> {
        match self.state() {
            ConnectionState::Established | ConnectionState::Draining => Ok(()),
            ConnectionState::Rejected | ConnectionState::Closed => Err(SessionError::Closed),
            ConnectionState::Handshaking => Err(SessionError::ProtocolViolation(
                "session is not established",
            )),
        }
    }

    fn forbid_stream_above_goaway(&self) -> Result<(), SessionError> {
        let Some(ceiling) = self.runtime.goaway_ceiling else {
            return Ok(());
        };
        let next = self.runtime.streams.peek_next_id()?;
        if next > ceiling {
            return Err(SessionError::ProtocolViolation(
                "no new stream above GOAWAY LastStreamID",
            ));
        }
        Ok(())
    }

    fn reject_peer_stream_above_goaway(&self, stream_id: StreamId) -> Result<(), SessionError> {
        if self
            .runtime
            .goaway_ceiling
            .is_some_and(|ceiling| stream_id.get() > ceiling)
        {
            return Err(SessionError::ProtocolViolation(
                "no new stream above GOAWAY LastStreamID",
            ));
        }
        Ok(())
    }

    fn complete_drain_if_idle(&mut self) -> Result<bool, SessionError> {
        if self.state() != ConnectionState::Draining {
            return Ok(false);
        }
        if self.runtime.streams.open_count() == 0 && self.runtime.datagrams.open_count() == 0 {
            self.close()?;
            return Ok(true);
        }
        Ok(false)
    }

    fn effects_none_after_progress(&mut self) -> Result<SessionEffects, SessionError> {
        Ok(SessionEffects {
            terminate: self.complete_drain_if_idle()?,
            ..SessionEffects::none()
        })
    }

    fn send_after_progress(&mut self, frame: Frame) -> Result<SessionEffects, SessionError> {
        Ok(SessionEffects {
            frames: vec![frame],
            terminate: self.complete_drain_if_idle()?,
            ..SessionEffects::default()
        })
    }

    /// Returns the identifier ceiling imposed by `GOAWAY`, if any.
    #[must_use]
    pub const fn goaway_ceiling(&self) -> Option<u32> {
        match &self.runtime.goaway_ceiling {
            Some(value) => Some(*value),
            None => None,
        }
    }

    /// Returns the current connection-level send credit.
    #[must_use]
    pub const fn connection_send_credit(&self) -> u64 {
        self.runtime.conn_send.remaining()
    }

    /// Returns `true` when this endpoint is the client.
    #[must_use]
    pub const fn is_client(&self) -> bool {
        matches!(self.config.role, Role::Client)
    }
}
