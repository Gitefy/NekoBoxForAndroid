//! Vela connection lifecycle state machine.
//!
//! The connection state machine is fail-closed (spec §16): every transition
//! not explicitly defined as legal is a protocol error that terminates the
//! connection. There is no path from a terminal state back to
//! [`ConnectionState::Established`].

use vela_proto::{GoAwayReason, SessionRejectReason};

use crate::control::SessionRuntime;
use crate::error::SessionError;

/// The role a session endpoint plays.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Noise initiator / proxy client.
    Client,
    /// Noise responder / proxy server.
    Server,
}

/// Vela connection lifecycle states.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// The Noise handshake is still in progress; no Vela frames may be exchanged.
    Handshaking,
    /// The session is authorized and frames may be exchanged.
    Established,
    /// `GOAWAY` has been sent or received; the connection is draining.
    Draining,
    /// `SESSION_REJECT` was sent or received; the connection is not reusable.
    Rejected,
    /// The connection is closed. Terminal.
    Closed,
}

impl ConnectionState {
    /// Returns `true` for states from which no further transition is legal.
    #[must_use]
    pub const fn is_terminal(self) -> bool {
        matches!(self, Self::Rejected | Self::Closed)
    }
}

/// Configuration for a Vela session endpoint.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionConfig {
    /// Role of this endpoint.
    pub role: Role,
    /// Receive credit advertised to the peer via `CONNECTION_MAX_DATA`.
    pub initial_receive_credit: u64,
    /// Configured ceiling on concurrently open streams.
    pub max_concurrent_streams: u32,
}

/// A Vela session endpoint: connection lifecycle state and role discipline.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Session {
    pub(crate) config: SessionConfig,
    state: ConnectionState,
    pub(crate) runtime: SessionRuntime,
}

impl Session {
    /// Creates a session in [`ConnectionState::Handshaking`].
    ///
    /// # Errors
    /// [`SessionError::InvalidConfiguration`] when a configuration value is
    /// invalid (recoverable; no state is mutated).
    pub fn new(config: SessionConfig) -> Result<Self, SessionError> {
        if config.max_concurrent_streams == 0 {
            return Err(SessionError::InvalidConfiguration {
                field: "max_concurrent_streams",
            });
        }
        let runtime = SessionRuntime::new(&config);
        Ok(Self {
            config,
            state: ConnectionState::Handshaking,
            runtime,
        })
    }

    /// Returns the current connection state.
    #[must_use]
    pub const fn state(&self) -> ConnectionState {
        self.state
    }

    /// Returns the session configuration.
    #[must_use]
    pub const fn config(&self) -> &SessionConfig {
        &self.config
    }

    /// Returns `true` once the connection can no longer be used.
    #[must_use]
    pub const fn is_terminated(&self) -> bool {
        self.state.is_terminal()
    }

    fn ensure_live(&self) -> Result<(), SessionError> {
        if self.state.is_terminal() {
            return Err(SessionError::Closed);
        }
        Ok(())
    }

    /// Server: authorize the session. Emits `SESSION_ACCEPT`.
    ///
    /// # Errors
    /// [`SessionError::RoleViolation`] when invoked by a client,
    /// [`SessionError::Closed`] on a terminated session, and
    /// [`SessionError::InvalidStateTransition`] when already established.
    pub fn accept(&mut self) -> Result<(), SessionError> {
        self.ensure_live()?;
        if self.config.role != Role::Server {
            return Err(SessionError::RoleViolation(
                "only a server may accept a session",
            ));
        }
        self.transition(ConnectionState::Established)
    }

    /// Server: deny the session. Emits `SESSION_REJECT` and terminates.
    ///
    /// # Errors
    /// [`SessionError::RoleViolation`] when invoked by a client, and
    /// [`SessionError::Closed`] on a terminated session.
    pub fn reject(&mut self, _reason: SessionRejectReason) -> Result<(), SessionError> {
        self.ensure_live()?;
        if self.config.role != Role::Server {
            return Err(SessionError::RoleViolation(
                "only a server may reject a session",
            ));
        }
        self.state = ConnectionState::Rejected;
        Ok(())
    }

    /// Client: process the server's `SESSION_ACCEPT`.
    ///
    /// # Errors
    /// [`SessionError::RoleViolation`] when invoked by a server, and
    /// [`SessionError::Closed`] on a terminated session.
    pub fn on_session_accept(&mut self) -> Result<(), SessionError> {
        self.ensure_live()?;
        if self.config.role != Role::Client {
            return Err(SessionError::RoleViolation(
                "only a client may process SESSION_ACCEPT",
            ));
        }
        self.transition(ConnectionState::Established)
    }

    /// Client: process the server's `SESSION_REJECT` and terminate.
    ///
    /// # Errors
    /// [`SessionError::RoleViolation`] when invoked by a server, and
    /// [`SessionError::Closed`] on a terminated session.
    pub fn on_session_reject(&mut self, _reason: u16) -> Result<(), SessionError> {
        self.ensure_live()?;
        if self.config.role != Role::Client {
            return Err(SessionError::RoleViolation(
                "only a client may process SESSION_REJECT",
            ));
        }
        self.state = ConnectionState::Rejected;
        Ok(())
    }

    /// Begin a graceful shutdown: emit `GOAWAY` and drain.
    ///
    /// # Errors
    /// [`SessionError::Closed`] on a terminated session and
    /// [`SessionError::InvalidStateTransition`] when not established.
    pub fn begin_drain(&mut self, _reason: GoAwayReason) -> Result<(), SessionError> {
        self.ensure_live()?;
        self.transition(ConnectionState::Draining)
    }

    /// Close the connection. Terminal.
    ///
    /// # Errors
    /// [`SessionError::Closed`] when already terminated.
    pub fn close(&mut self) -> Result<(), SessionError> {
        self.ensure_live()?;
        self.state = ConnectionState::Closed;
        Ok(())
    }

    fn transition(&mut self, to: ConnectionState) -> Result<(), SessionError> {
        let legal = matches!(
            (self.state, to),
            (ConnectionState::Handshaking, ConnectionState::Established)
                | (
                    ConnectionState::Established,
                    ConnectionState::Draining | ConnectionState::Closed
                )
                | (ConnectionState::Draining, ConnectionState::Closed)
        );
        if !legal {
            return Err(SessionError::InvalidStateTransition {
                from: self.state,
                to,
            });
        }
        self.state = to;
        Ok(())
    }
}
