//! The authenticated session frame pump.
//!
//! `SessionDriver` couples a `vela-session` [`Session`] with a
//! [`RecordChannel`]. It never invents protocol state: every outbound byte
//! originates from a `SessionEffects` value, and rekey execution happens at
//! the exact points mandated by spec §8 (after `KEY_UPDATE` is written for
//! the send direction; after it is processed for the receive direction).

use std::io::{Read, Write};
use std::time::{Duration, Instant};

use vela_crypto::keys::{
    ClientStaticPrivateKey, ClientStaticPublicKey, ServerStaticPrivateKey, ServerStaticPublicKey,
};
use vela_proto::{
    ContextId, DnsRequestId, Endpoint, Frame, GoAwayReason, OpenResultStatus, SessionRejectReason,
    StreamId, StreamResetCode,
};
use vela_session::connection::ConnectionState;
use vela_session::{Session, SessionConfig, SessionEffects};
use vela_wire::frame::{decode_frame, encode_frame};

use crate::error::TransportError;
use crate::handshake::{client_handshake, server_handshake};
use crate::record::RecordChannel;

/// Report of one inbound pump cycle.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct PumpReport {
    /// `STREAM_DATA` payloads accepted by the session layer, in order.
    pub delivered: Vec<(StreamId, Vec<u8>)>,
    /// Discarded receive bytes whose connection credit can be released.
    pub discarded_stream_bytes: u64,
    /// Datagram payloads accepted on ACTIVE contexts.
    pub delivered_datagrams: Vec<(ContextId, Vec<u8>)>,
    /// Inbound DNS queries (server side), surfaced to the DNS subsystem.
    pub dns_queries: Vec<(DnsRequestId, Vec<u8>)>,
    /// DNS responses matched to outstanding requests (client side).
    pub dns_responses: Vec<(DnsRequestId, Vec<u8>)>,
    /// Inbound stream open requests from the peer that need a policy decision.
    pub inbound_stream_opens: Vec<(StreamId, Endpoint)>,
    /// Inbound datagram context open requests from the peer that need a policy
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
    pub datagram_closes: Vec<(ContextId, vela_proto::DatagramCloseReason)>,
}

/// Production rekey thresholds from frozen spec §14 (profile defaults).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RekeyPolicy {
    /// Send-direction records before `KEY_UPDATE`.
    pub max_records: u64,
    /// Protected plaintext bytes before `KEY_UPDATE`.
    pub max_bytes: u64,
    /// Key age before `KEY_UPDATE`.
    pub max_age: Duration,
}

impl RekeyPolicy {
    /// Spec §14 recommended starting thresholds: 2^20 records, 1 GiB, 30 minutes.
    #[must_use]
    pub const fn production() -> Self {
        Self {
            max_records: 1 << 20,
            max_bytes: 1 << 30,
            max_age: Duration::from_secs(30 * 60),
        }
    }

    fn due(self, records: u64, bytes: u64, last_rekey: Instant) -> bool {
        records >= self.max_records
            || bytes >= self.max_bytes
            || last_rekey.elapsed() >= self.max_age
    }
}

/// Recommended absolute session lifetime from frozen spec §14.
pub const PRODUCTION_SESSION_LIFETIME: Duration = Duration::from_secs(24 * 60 * 60);

#[derive(Debug)]
struct PaddingPrng {
    state: u64,
}

impl PaddingPrng {
    fn new() -> Self {
        use std::time::{SystemTime, UNIX_EPOCH};
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0x5645_4c41);
        Self {
            state: u64::try_from(nanos & u128::from(u64::MAX)).unwrap_or(0) ^ 0x9E37_79B9_7F4A_7C15,
        }
    }

    fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn next_range(&mut self, min: usize, max: usize) -> usize {
        if min >= max {
            return min;
        }
        let diff = max - min + 1;
        min + usize::try_from(self.next_u64() % u64::try_from(diff).unwrap_or(u64::MAX))
            .unwrap_or(0)
    }

    fn fill_padding(&mut self, buf: &mut [u8]) {
        let mut i = 0;
        while i < buf.len() {
            let val = self.next_u64().to_le_bytes();
            let take = (buf.len() - i).min(8);
            buf[i..i + take].copy_from_slice(&val[..take]);
            i += take;
        }
    }
}

/// A bidirectional session endpoint driving a record channel.
#[derive(Debug)]
pub struct SessionDriver<S> {
    session: Session,
    channel: RecordChannel<S>,
    terminated: bool,
    rekey: RekeyPolicy,
    send_records: u64,
    send_bytes: u64,
    last_rekey_at: Instant,
    max_lifetime: Duration,
    session_started_at: Instant,
    traffic_morphing: bool,
    padding_prng: PaddingPrng,
    /// When true, outbound records stay buffered until [`SessionDriver::flush_outbound`].
    defer_flush: bool,
}

impl<S: Read + Write> SessionDriver<S> {
    /// Client: drive the handshake, then wait for the server's
    /// `SESSION_ACCEPT` (spec §8). The follow-up `CONNECTION_MAX_DATA` record
    /// is consumed by the next [`SessionDriver::pump_inbound`] call.
    ///
    /// # Errors
    /// Terminal on any handshake, framing, cryptographic, or session failure.
    pub fn connect_client(
        io: S,
        client_static: &ClientStaticPrivateKey,
        server_static: &ServerStaticPublicKey,
        config: SessionConfig,
    ) -> Result<Self, TransportError> {
        let (channel, _) = client_handshake(io, client_static, server_static)?;
        let mut driver = Self::assembled(Session::new(config)?, channel);
        driver.pump_inbound()?;
        if driver.session.state() != ConnectionState::Established {
            return Err(TransportError::Session(
                vela_session::SessionError::ProtocolViolation(
                    "session was not established by SESSION_ACCEPT",
                ),
            ));
        }
        Ok(driver)
    }

    /// Server: drive the handshake, then authorize the session by emitting
    /// `SESSION_ACCEPT` followed by `CONNECTION_MAX_DATA` (spec §8, §9).
    ///
    /// This convenience method authorizes every authenticated client key. For
    /// production authorization, use [`SessionDriver::serve_server_with_authorizer`].
    ///
    /// # Errors
    /// Terminal on any handshake, framing, cryptographic, or session failure.
    pub fn serve_server(
        io: S,
        server_static: &ServerStaticPrivateKey,
        config: SessionConfig,
    ) -> Result<Self, TransportError> {
        Self::serve_server_with_authorizer(io, server_static, config, |_| true).map(|(d, _)| d)
    }

    /// Server: drive the handshake, authorize the authenticated client static
    /// public key, and then either accept or reject the session.
    ///
    /// On success, returns the established driver together with the
    /// authenticated client static public key for per-request policy.
    ///
    /// # Errors
    /// Terminal on any handshake, framing, cryptographic, session, or
    /// authorization failure.
    pub fn serve_server_with_authorizer<F>(
        io: S,
        server_static: &ServerStaticPrivateKey,
        config: SessionConfig,
        authorize: F,
    ) -> Result<(Self, ClientStaticPublicKey), TransportError>
    where
        F: FnOnce(&ClientStaticPublicKey) -> bool,
    {
        Self::serve_server_with_authorizer_replay(io, server_static, config, authorize, None)
    }

    /// Same as [`SessionDriver::serve_server_with_authorizer`], with optional
    /// first-flight replay suppression before M2 is written.
    ///
    /// # Errors
    /// Terminal on any handshake, framing, cryptographic, session, replay, or
    /// authorization failure.
    pub fn serve_server_with_authorizer_replay<F>(
        io: S,
        server_static: &ServerStaticPrivateKey,
        config: SessionConfig,
        authorize: F,
        replay_guard: Option<&crate::replay::HandshakeReplayGuard>,
    ) -> Result<(Self, ClientStaticPublicKey), TransportError>
    where
        F: FnOnce(&ClientStaticPublicKey) -> bool,
    {
        let (channel, _, client_static) =
            crate::handshake::server_handshake_with_replay_guard(io, server_static, replay_guard)?;
        let mut driver = Self::assembled(Session::new(config)?, channel);
        if !authorize(&client_static) {
            let effects = driver
                .session
                .reject_with_effects(SessionRejectReason::AuthorizationFailed)?;
            driver.execute(effects)?;
            return Err(TransportError::AuthorizationFailed);
        }
        let effects = driver.session.accept_with_effects()?;
        driver.execute(effects)?;
        Ok((driver, client_static))
    }

    /// Server: reject the session and terminate.
    ///
    /// # Errors
    /// Terminal on any failure.
    pub fn reject_client(
        io: S,
        server_static: &ServerStaticPrivateKey,
        config: SessionConfig,
        reason: SessionRejectReason,
    ) -> Result<Self, TransportError> {
        let (channel, _, _) = server_handshake(io, server_static)?;
        let mut driver = Self::assembled(Session::new(config)?, channel);
        let effects = driver.session.reject_with_effects(reason)?;
        driver.execute(effects)?;
        Ok(driver)
    }

    /// Reads, decrypts, decodes, and dispatches exactly one inbound record.
    ///
    /// # Errors
    /// Terminal on any failure; the connection is unusable afterwards.
    pub fn pump_inbound(&mut self) -> Result<PumpReport, TransportError> {
        self.run(|this| {
            let plaintext = this.channel.read_record()?;
            let decoded = decode_frame(&plaintext)?;
            let effects = this.session.handle_frame(decoded.frame)?;
            this.execute(effects)
        })
    }

    /// Opens a stream toward `endpoint` and writes `STREAM_OPEN`.
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn open_stream(
        &mut self,
        endpoint: Endpoint,
        initial_max_data: u64,
    ) -> Result<StreamId, TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let (id, effects) = self.session.open_stream(endpoint, initial_max_data)?;
        self.execute(effects)?;
        Ok(id)
    }

    /// Server: accepts a peer-initiated stream (emits `STREAM_OPEN_RESULT`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn accept_stream(
        &mut self,
        id: StreamId,
        initial_max_data: u64,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.accept_stream(id, initial_max_data)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Server: rejects a peer-initiated stream (emits `STREAM_OPEN_RESULT`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn reject_stream(
        &mut self,
        id: StreamId,
        status: OpenResultStatus,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.reject_stream(id, status)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Sends `STREAM_DATA` under connection and stream credit accounting.
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn send_stream_data(&mut self, id: StreamId, data: Vec<u8>) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.send_stream_data(id, data)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Server: grants additional receive credit to the peer for a stream
    /// (emits `STREAM_MAX_DATA`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn grant_stream_credit(
        &mut self,
        id: StreamId,
        max_data: u64,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.grant_stream_credit(id, max_data)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Grants additional connection receive credit to the peer
    /// (emits `CONNECTION_MAX_DATA`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn grant_connection_credit(&mut self, max_data: u64) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.grant_connection_credit(max_data)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Returns the connection-level remaining receive credit.
    #[must_use]
    pub fn conn_receive_credit(&self) -> u64 {
        self.session.conn_receive_credit()
    }

    /// Returns the connection-level (received, advertised) cumulative bytes.
    #[must_use]
    pub fn conn_receive_window(&self) -> (u64, u64) {
        self.session.conn_receive_window()
    }

    /// Returns the connection-level remaining send credit.
    #[must_use]
    pub fn conn_send_credit(&self) -> u64 {
        self.session.conn_send_credit()
    }

    /// Returns the remaining send credit for a stream.
    ///
    /// # Errors
    /// Terminal on unknown streams.
    pub fn stream_send_credit(&self, id: StreamId) -> Result<u64, TransportError> {
        Ok(self.session.stream_send_credit(id)?)
    }

    /// Returns the remaining receive credit for a stream.
    ///
    /// # Errors
    /// Terminal on unknown streams.
    pub fn stream_receive_credit(&self, id: StreamId) -> Result<u64, TransportError> {
        Ok(self.session.stream_receive_credit(id)?)
    }

    /// Returns the (received, advertised) cumulative bytes for a stream.
    ///
    /// # Errors
    /// Terminal on unknown streams.
    pub fn stream_receive_window(&self, id: StreamId) -> Result<(u64, u64), TransportError> {
        Ok(self.session.stream_receive_window(id)?)
    }

    /// Half-closes a stream locally (emits `STREAM_FIN`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn finish_stream(&mut self, id: StreamId) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.finish_stream(id)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Cancels both directions of a stream.
    /// # Errors
    /// Returns session or transport errors.
    pub fn reset_stream(
        &mut self,
        id: StreamId,
        error: StreamResetCode,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.reset_stream(id, error)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Sends a `PING`.
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn send_ping(&mut self, opaque: u64) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.send_ping(opaque)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Returns whether any PING is currently outstanding awaiting PONG.
    #[must_use]
    pub fn has_pending_pings(&self) -> bool {
        self.session.has_pending_pings()
    }

    /// Initiates a rekey: writes `KEY_UPDATE` under the old key, then rekeys
    /// the send direction immediately after (spec §8).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn initiate_key_update(&mut self) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.initiate_key_update()?;
        self.execute(effects)?;
        Ok(())
    }

    /// Opens a datagram context and writes `DATAGRAM_OPEN`.
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn open_datagram(&mut self, endpoint: Endpoint) -> Result<ContextId, TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let (context_id, effects) = self.session.open_datagram(endpoint)?;
        self.execute(effects)?;
        Ok(context_id)
    }

    /// Answers a peer `DATAGRAM_OPEN` (writes `DATAGRAM_OPEN_RESULT`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn answer_datagram(
        &mut self,
        context_id: ContextId,
        status: vela_proto::OpenResultStatus,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.answer_datagram(context_id, status)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Sends `DATAGRAM_DATA` on an ACTIVE context.
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn send_datagram(
        &mut self,
        context_id: ContextId,
        payload: Vec<u8>,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.send_datagram(context_id, payload)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Closes a datagram context (writes `DATAGRAM_CLOSE`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn close_datagram(
        &mut self,
        context_id: ContextId,
        reason: vela_proto::DatagramCloseReason,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.close_datagram(context_id, reason)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Sends a `DNS_QUERY` and returns the allocated request ID.
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn send_dns_query(&mut self, message: Vec<u8>) -> Result<DnsRequestId, TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let (request_id, effects) = self.session.send_dns_query(message)?;
        self.execute(effects)?;
        Ok(request_id)
    }

    /// Server: answers an inbound `DNS_QUERY` (writes `DNS_RESPONSE`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn send_dns_response(
        &mut self,
        request_id: DnsRequestId,
        message: Vec<u8>,
    ) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.send_dns_response(request_id, message)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Cancels an outstanding `DNS_QUERY` (writes `DNS_CANCEL`).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn cancel_dns_query(&mut self, request_id: DnsRequestId) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.cancel_dns_query(request_id)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Sends `GOAWAY` and enters the draining state (spec §8 / §14).
    ///
    /// # Errors
    /// Terminal on any session or I/O failure.
    pub fn send_goaway(&mut self, reason: GoAwayReason) -> Result<(), TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let effects = self.session.goaway_with_effects(reason)?;
        self.execute(effects)?;
        Ok(())
    }

    /// Overrides the send-direction rekey thresholds (tests and specialised profiles).
    pub fn set_rekey_policy(&mut self, policy: RekeyPolicy) {
        self.rekey = policy;
    }

    /// Overrides the absolute session lifetime (tests). Spec §14 default is 24 hours.
    pub fn set_max_lifetime(&mut self, max_lifetime: Duration) {
        self.max_lifetime = max_lifetime;
    }

    /// Returns `true` once the connection has terminated.
    #[must_use]
    pub const fn is_terminated(&self) -> bool {
        self.terminated
    }

    fn execute(&mut self, effects: SessionEffects) -> Result<PumpReport, TransportError> {
        let already_rekeyed = effects.rekey_send;
        let report = match self.execute_inner(effects) {
            Ok(report) => report,
            Err(error) => {
                self.terminated = true;
                return Err(error);
            }
        };
        if !already_rekeyed && !self.terminated {
            self.maybe_rekey()?;
        }
        if !self.terminated {
            self.maybe_lifetime_goaway()?;
        }
        Ok(report)
    }

    fn maybe_rekey(&mut self) -> Result<(), TransportError> {
        if !self
            .rekey
            .due(self.send_records, self.send_bytes, self.last_rekey_at)
        {
            return Ok(());
        }
        self.send_records = 0;
        self.send_bytes = 0;
        self.last_rekey_at = Instant::now();
        let effects = self.session.initiate_key_update()?;
        let _ = self.execute_inner(effects)?;
        Ok(())
    }

    fn maybe_lifetime_goaway(&mut self) -> Result<(), TransportError> {
        if self.session.state() != ConnectionState::Established {
            return Ok(());
        }
        if self.session_started_at.elapsed() < self.max_lifetime {
            return Ok(());
        }
        let effects = self
            .session
            .goaway_with_effects(GoAwayReason::SessionLifetime)?;
        let _ = self.execute_inner(effects)?;
        Ok(())
    }

    /// Enables or disables dynamic traffic morphing and frame padding
    /// for anti-censorship and traffic analysis resistance.
    pub fn enable_traffic_morphing(&mut self, enabled: bool) {
        self.traffic_morphing = enabled;
    }

    /// Defers per-frame TCP flushes so the proxy loop can coalesce writes.
    pub fn set_defer_flush(&mut self, defer: bool) {
        self.defer_flush = defer;
    }

    /// Flushes any deferred outbound ciphertext to the wire.
    ///
    /// # Errors
    /// Terminal on I/O failure.
    pub fn flush_outbound(&mut self) -> Result<(), TransportError> {
        self.run(|this| this.channel.flush())
    }

    fn generate_frame_padding(&mut self, frame: &Frame) -> Vec<u8> {
        let pad_len = match frame {
            Frame::Ping { .. } | Frame::Pong { .. } => self.padding_prng.next_range(16, 64),
            Frame::SessionAccept | Frame::ConnectionMaxData { .. } => {
                self.padding_prng.next_range(24, 80)
            }
            Frame::StreamOpen { .. } | Frame::StreamOpenResult { .. } => {
                self.padding_prng.next_range(16, 64)
            }
            Frame::StreamFin { .. } | Frame::StreamReset { .. } => {
                self.padding_prng.next_range(16, 48)
            }
            _ => 0,
        };
        if pad_len == 0 {
            return Vec::new();
        }
        let mut pad = vec![0u8; pad_len];
        self.padding_prng.fill_padding(&mut pad);
        pad
    }

    fn assembled(session: Session, channel: RecordChannel<S>) -> Self {
        let now = Instant::now();
        Self {
            session,
            channel,
            terminated: false,
            rekey: RekeyPolicy::production(),
            send_records: 0,
            send_bytes: 0,
            last_rekey_at: now,
            max_lifetime: PRODUCTION_SESSION_LIFETIME,
            session_started_at: now,
            traffic_morphing: false,
            padding_prng: PaddingPrng::new(),
            defer_flush: false,
        }
    }

    fn execute_inner(&mut self, effects: SessionEffects) -> Result<PumpReport, TransportError> {
        let report = PumpReport {
            delivered: effects.delivered,
            discarded_stream_bytes: effects.discarded_stream_bytes,
            delivered_datagrams: effects.delivered_datagrams,
            dns_queries: effects.dns_queries,
            dns_responses: effects.dns_responses,
            inbound_stream_opens: effects.inbound_stream_opens,
            inbound_datagram_opens: effects.inbound_datagram_opens,
            stream_fins: effects.stream_fins,
            stream_resets: effects.stream_resets,
            stream_open_results: effects.stream_open_results,
            datagram_open_results: effects.datagram_open_results,
            datagram_closes: effects.datagram_closes,
        };
        for frame in &effects.frames {
            let plaintext = if self.traffic_morphing {
                let padding = self.generate_frame_padding(frame);
                encode_frame(frame, &padding)?
            } else {
                encode_frame(frame, &[])?
            };
            self.send_records = self.send_records.saturating_add(1);
            self.send_bytes = self
                .send_bytes
                .saturating_add(u64::try_from(plaintext.len()).unwrap_or(u64::MAX));
            self.channel.write_record_unflushed(&plaintext)?;
        }
        // Once terminated, no later pump/flush call can drain buffered records.
        // Preserve the final GOAWAY/FIN even when regular writes are coalesced.
        if !self.defer_flush || effects.terminate {
            self.channel.flush()?;
        }
        // Spec §8: rekey the send direction immediately after `KEY_UPDATE` is
        // written; rekey the receive direction immediately after it is
        // processed. No nonce reset and no speculative trial.
        if effects.rekey_send {
            self.channel.rekey_send()?;
        }
        if effects.rekey_recv {
            self.channel.rekey_recv()?;
        }
        if effects.terminate {
            self.terminated = true;
        }
        Ok(report)
    }

    /// Runs a driver operation, marking the connection terminated on any error
    /// so callers (including the connection pool) observe fail-closed health.
    fn run<T>(
        &mut self,
        op: impl FnOnce(&mut Self) -> Result<T, TransportError>,
    ) -> Result<T, TransportError> {
        if self.terminated {
            return Err(TransportError::Closed);
        }
        let result = op(self);
        if let Err(ref e) = result {
            let is_transient_timeout = match e {
                TransportError::Io(io_err) => {
                    io_err.kind() == std::io::ErrorKind::TimedOut
                        || io_err.kind() == std::io::ErrorKind::WouldBlock
                }
                _ => false,
            };
            if self.channel.is_poisoned() || (!is_transient_timeout && e.is_terminal()) {
                self.terminated = true;
            }
        }
        result
    }

    /// Returns true if the connection has not been terminated by a terminal
    /// error or explicit close.
    #[must_use]
    pub fn is_healthy(&self) -> bool {
        !self.terminated
    }

    /// Returns a mutable reference to the underlying byte stream.
    #[must_use]
    pub fn stream_mut(&mut self) -> &mut S {
        self.channel.inner_mut()
    }

    /// Returns `true` when the underlying session is established.
    #[must_use]
    pub const fn is_established(&self) -> bool {
        matches!(self.session.state(), ConnectionState::Established)
    }
}
