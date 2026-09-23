//! TCP1 socket runtime for Vela (spec §3, §4, §5).
//!
//! This module couples the I/O-agnostic `SessionDriver` from RI-4 with real
//! `std::net::TcpStream` / `TcpListener` endpoints, connect/handshake timeouts,
//! and the mandatory server-side client authorization hook.

use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use vela_crypto::keys::{
    ClientStaticPrivateKey, ClientStaticPublicKey, ServerStaticPrivateKey, ServerStaticPublicKey,
};
use vela_session::SessionConfig;

use crate::TransportError;
use crate::driver::SessionDriver;
use crate::replay::HandshakeReplayGuard;

/// A [`SessionDriver`] backed by a real TCP socket.
pub type TcpSessionDriver = SessionDriver<TcpStream>;

/// Configuration for a client TCP connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ConnectConfig {
    /// Server address to connect to.
    pub server_addr: SocketAddr,
    /// Maximum time to wait for the TCP connection to be established.
    pub connect_timeout: Duration,
    /// Per-message read/write timeout applied during the Noise handshake.
    pub handshake_timeout: Duration,
}

/// Configuration for a server TCP listener.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ListenConfig {
    /// Local address to bind to.
    pub bind_addr: SocketAddr,
    /// Per-message read/write timeout applied during the Noise handshake.
    pub handshake_timeout: Duration,
}

/// A bound TCP1 listener that accepts authenticated Vela sessions.
#[derive(Debug)]
pub struct TcpServer {
    listener: TcpListener,
    handshake_timeout: Duration,
    replay_guard: Arc<HandshakeReplayGuard>,
}

impl TcpServer {
    /// Binds a TCP listener to the configured local address.
    ///
    /// # Errors
    /// `TransportError::Io` on bind failure.
    pub fn bind(config: &ListenConfig) -> Result<Self, TransportError> {
        let listener = TcpListener::bind(config.bind_addr)?;
        Ok(Self {
            listener,
            handshake_timeout: config.handshake_timeout,
            replay_guard: Arc::new(HandshakeReplayGuard::production()),
        })
    }

    /// Replaces the shared first-flight replay guard (tests / specialised deployments).
    pub fn set_replay_guard(&mut self, guard: Arc<HandshakeReplayGuard>) {
        self.replay_guard = guard;
    }

    /// Returns the shared first-flight replay guard.
    #[must_use]
    pub fn replay_guard(&self) -> Arc<HandshakeReplayGuard> {
        Arc::clone(&self.replay_guard)
    }

    /// Returns the local socket address the listener is bound to.
    ///
    /// # Errors
    /// `TransportError::Io` on address retrieval failure.
    pub fn local_addr(&self) -> Result<SocketAddr, TransportError> {
        Ok(self.listener.local_addr()?)
    }

    /// Blocks until a client connects, performs the Noise XK handshake, runs
    /// the provided authorization callback, and returns an established session
    /// driver for authorized clients.
    ///
    /// If authorization returns `false`, the server emits `SESSION_REJECT` and
    /// closes the TCP connection.
    ///
    /// # Errors
    /// Terminal on any I/O, handshake, framing, cryptographic, session, or
    /// authorization failure.
    pub fn accept<F>(
        &self,
        server_static: &ServerStaticPrivateKey,
        session_config: SessionConfig,
        authorize: F,
    ) -> Result<SessionDriver<TcpStream>, TransportError>
    where
        F: FnOnce(&ClientStaticPublicKey) -> bool,
    {
        self.accept_with_identity(server_static, session_config, authorize)
            .map(|(driver, _)| driver)
    }

    /// Same as [`TcpServer::accept`], but also returns the authenticated
    /// client static public key so the caller can apply per-request policy.
    ///
    /// # Errors
    /// Terminal on any I/O, handshake, framing, cryptographic, session, or
    /// authorization failure.
    pub fn accept_with_identity<F>(
        &self,
        server_static: &ServerStaticPrivateKey,
        session_config: SessionConfig,
        authorize: F,
    ) -> Result<(SessionDriver<TcpStream>, ClientStaticPublicKey), TransportError>
    where
        F: FnOnce(&ClientStaticPublicKey) -> bool,
    {
        let (stream, _peer) = self.listener.accept()?;
        self.finish_accept(stream, server_static, session_config, authorize)
    }

    /// Non-blocking variant of [`TcpServer::accept_with_identity`].
    ///
    /// Returns `Ok(None)` when no inbound connection is pending, which lets an
    /// accept loop poll a shutdown flag instead of blocking forever. The
    /// listener is switched to non-blocking mode only for the duration of the
    /// underlying `accept` call.
    ///
    /// # Errors
    /// Terminal on any I/O failure other than "would block".
    pub fn try_accept_with_identity<F>(
        &self,
        server_static: &ServerStaticPrivateKey,
        session_config: SessionConfig,
        authorize: F,
    ) -> Result<Option<(SessionDriver<TcpStream>, ClientStaticPublicKey)>, TransportError>
    where
        F: FnOnce(&ClientStaticPublicKey) -> bool,
    {
        self.listener.set_nonblocking(true)?;
        let accepted = self.listener.accept();
        self.listener.set_nonblocking(false)?;

        match accepted {
            Ok((stream, _peer)) => self
                .finish_accept(stream, server_static, session_config, authorize)
                .map(Some),
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Ok(None),
            Err(e) => Err(TransportError::Io(e)),
        }
    }

    /// Returns an iterator over incoming TCP connections without blocking
    /// on handshakes.
    pub fn incoming(&self) -> std::net::Incoming<'_> {
        self.listener.incoming()
    }

    /// Performs the Noise handshake and authorization on an accepted TCP stream.
    ///
    /// # Errors
    /// Terminal on handshake timeout, framing, crypto, or authorization failure.
    pub fn finish_accept<F>(
        &self,
        stream: TcpStream,
        server_static: &ServerStaticPrivateKey,
        session_config: SessionConfig,
        authorize: F,
    ) -> Result<(SessionDriver<TcpStream>, ClientStaticPublicKey), TransportError>
    where
        F: FnOnce(&ClientStaticPublicKey) -> bool,
    {
        // An accepted socket may inherit non-blocking mode from the listener on
        // some platforms; the handshake requires blocking semantics.
        stream.set_nonblocking(false)?;
        tune_wan_socket(&stream);
        set_timeouts(&stream, Some(self.handshake_timeout))?;
        let (mut driver, client_static) = SessionDriver::serve_server_with_authorizer_replay(
            stream,
            server_static,
            session_config,
            authorize,
            Some(&self.replay_guard),
        )?;
        set_timeouts(driver.stream_mut(), None)?;
        Ok((driver, client_static))
    }
}

/// Connects to a Vela server over TCP, performs the Noise XK handshake, and
/// returns an established client session driver.
///
/// # Errors
/// Terminal on connect timeout, handshake timeout, or any cryptographic/
/// session failure.
pub fn connect_client(
    config: &ConnectConfig,
    client_static: &ClientStaticPrivateKey,
    server_static: &ServerStaticPublicKey,
    session_config: SessionConfig,
) -> Result<SessionDriver<TcpStream>, TransportError> {
    let stream = TcpStream::connect_timeout(&config.server_addr, config.connect_timeout)?;
    tune_wan_socket(&stream);
    set_timeouts(&stream, Some(config.handshake_timeout))?;
    let mut driver =
        SessionDriver::connect_client(stream, client_static, server_static, session_config)?;
    set_timeouts(driver.stream_mut(), None)?;
    Ok(driver)
}

fn set_timeouts(stream: &TcpStream, timeout: Option<Duration>) -> Result<(), TransportError> {
    stream.set_read_timeout(timeout)?;
    stream.set_write_timeout(timeout)?;
    Ok(())
}

fn tune_wan_socket(stream: &TcpStream) {
    let _ = stream.set_nodelay(true);
}
