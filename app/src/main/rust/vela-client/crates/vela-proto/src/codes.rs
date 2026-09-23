//! Protocol result and reason code registries.

/// Reason codes for `SESSION_REJECT` frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum SessionRejectReason {
    AuthorizationFailed,
    ServerBusy,
    PolicyMismatch,
    InternalError,
    Unknown(u16),
}

impl SessionRejectReason {
    #[must_use]
    pub const fn from_u16(value: u16) -> Self {
        match value {
            0x0001 => Self::AuthorizationFailed,
            0x0002 => Self::ServerBusy,
            0x0003 => Self::PolicyMismatch,
            0x0004 => Self::InternalError,
            other => Self::Unknown(other),
        }
    }

    #[must_use]
    pub const fn as_u16(self) -> u16 {
        match self {
            Self::AuthorizationFailed => 0x0001,
            Self::ServerBusy => 0x0002,
            Self::PolicyMismatch => 0x0003,
            Self::InternalError => 0x0004,
            Self::Unknown(v) => v,
        }
    }

    #[must_use]
    pub const fn is_assigned(self) -> bool {
        !matches!(self, Self::Unknown(_))
    }
}

impl From<u16> for SessionRejectReason {
    fn from(code: u16) -> Self {
        Self::from_u16(code)
    }
}

impl From<SessionRejectReason> for u16 {
    fn from(reason: SessionRejectReason) -> Self {
        reason.as_u16()
    }
}

/// Status codes for `STREAM_OPEN_RESULT` and `DATAGRAM_OPEN_RESULT` frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum OpenResultStatus {
    Ok,
    GeneralFailure,
    PolicyDenied,
    NetworkUnreachable,
    HostUnreachable,
    ConnectionRefused,
    Timeout,
    AddressInvalid,
    ResourceLimit,
    Unknown(u16),
}

impl OpenResultStatus {
    #[must_use]
    pub const fn from_u16(value: u16) -> Self {
        match value {
            0x0000 => Self::Ok,
            0x0001 => Self::GeneralFailure,
            0x0002 => Self::PolicyDenied,
            0x0003 => Self::NetworkUnreachable,
            0x0004 => Self::HostUnreachable,
            0x0005 => Self::ConnectionRefused,
            0x0006 => Self::Timeout,
            0x0007 => Self::AddressInvalid,
            0x0008 => Self::ResourceLimit,
            other => Self::Unknown(other),
        }
    }

    #[must_use]
    pub const fn as_u16(self) -> u16 {
        match self {
            Self::Ok => 0x0000,
            Self::GeneralFailure => 0x0001,
            Self::PolicyDenied => 0x0002,
            Self::NetworkUnreachable => 0x0003,
            Self::HostUnreachable => 0x0004,
            Self::ConnectionRefused => 0x0005,
            Self::Timeout => 0x0006,
            Self::AddressInvalid => 0x0007,
            Self::ResourceLimit => 0x0008,
            Self::Unknown(v) => v,
        }
    }

    #[must_use]
    pub const fn is_assigned(self) -> bool {
        !matches!(self, Self::Unknown(_))
    }

    #[must_use]
    pub const fn is_success(self) -> bool {
        matches!(self, Self::Ok)
    }
}

impl From<u16> for OpenResultStatus {
    fn from(code: u16) -> Self {
        Self::from_u16(code)
    }
}

impl From<OpenResultStatus> for u16 {
    fn from(status: OpenResultStatus) -> Self {
        status.as_u16()
    }
}

/// Reason codes for `GOAWAY` frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum GoAwayReason {
    Normal,
    SessionLifetime,
    ServerShutdown,
    CredentialRevoked,
    ResourceLimit,
    ProtocolError,
    InternalError,
    Unknown(u16),
}

impl GoAwayReason {
    #[must_use]
    pub const fn from_u16(value: u16) -> Self {
        match value {
            0x0000 => Self::Normal,
            0x0001 => Self::SessionLifetime,
            0x0002 => Self::ServerShutdown,
            0x0003 => Self::CredentialRevoked,
            0x0004 => Self::ResourceLimit,
            0x0005 => Self::ProtocolError,
            0x0006 => Self::InternalError,
            other => Self::Unknown(other),
        }
    }

    #[must_use]
    pub const fn as_u16(self) -> u16 {
        match self {
            Self::Normal => 0x0000,
            Self::SessionLifetime => 0x0001,
            Self::ServerShutdown => 0x0002,
            Self::CredentialRevoked => 0x0003,
            Self::ResourceLimit => 0x0004,
            Self::ProtocolError => 0x0005,
            Self::InternalError => 0x0006,
            Self::Unknown(v) => v,
        }
    }

    #[must_use]
    pub const fn is_assigned(self) -> bool {
        !matches!(self, Self::Unknown(_))
    }
}

impl From<u16> for GoAwayReason {
    fn from(code: u16) -> Self {
        Self::from_u16(code)
    }
}

impl From<GoAwayReason> for u16 {
    fn from(reason: GoAwayReason) -> Self {
        reason.as_u16()
    }
}

/// Error codes for `STREAM_RESET` frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum StreamResetCode {
    General,
    Cancelled,
    ConnectFailed,
    Policy,
    FlowControl,
    ResourceLimit,
    ApplicationError,
    Unknown(u16),
}

impl StreamResetCode {
    #[must_use]
    pub const fn from_u16(value: u16) -> Self {
        match value {
            0x0000 => Self::General,
            0x0001 => Self::Cancelled,
            0x0002 => Self::ConnectFailed,
            0x0003 => Self::Policy,
            0x0004 => Self::FlowControl,
            0x0005 => Self::ResourceLimit,
            0x0006 => Self::ApplicationError,
            other => Self::Unknown(other),
        }
    }

    #[must_use]
    pub const fn as_u16(self) -> u16 {
        match self {
            Self::General => 0x0000,
            Self::Cancelled => 0x0001,
            Self::ConnectFailed => 0x0002,
            Self::Policy => 0x0003,
            Self::FlowControl => 0x0004,
            Self::ResourceLimit => 0x0005,
            Self::ApplicationError => 0x0006,
            Self::Unknown(v) => v,
        }
    }

    #[must_use]
    pub const fn is_assigned(self) -> bool {
        !matches!(self, Self::Unknown(_))
    }
}

impl From<u16> for StreamResetCode {
    fn from(code: u16) -> Self {
        Self::from_u16(code)
    }
}

impl From<StreamResetCode> for u16 {
    fn from(reset: StreamResetCode) -> Self {
        reset.as_u16()
    }
}

/// Reason codes for `DATAGRAM_CLOSE` frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum DatagramCloseReason {
    Normal,
    IdleTimeout,
    Policy,
    NetworkError,
    ResourceLimit,
    Cancelled,
    Unknown(u16),
}

impl DatagramCloseReason {
    #[must_use]
    pub const fn from_u16(value: u16) -> Self {
        match value {
            0x0000 => Self::Normal,
            0x0001 => Self::IdleTimeout,
            0x0002 => Self::Policy,
            0x0003 => Self::NetworkError,
            0x0004 => Self::ResourceLimit,
            0x0005 => Self::Cancelled,
            other => Self::Unknown(other),
        }
    }

    #[must_use]
    pub const fn as_u16(self) -> u16 {
        match self {
            Self::Normal => 0x0000,
            Self::IdleTimeout => 0x0001,
            Self::Policy => 0x0002,
            Self::NetworkError => 0x0003,
            Self::ResourceLimit => 0x0004,
            Self::Cancelled => 0x0005,
            Self::Unknown(v) => v,
        }
    }

    #[must_use]
    pub const fn is_assigned(self) -> bool {
        !matches!(self, Self::Unknown(_))
    }
}

impl From<u16> for DatagramCloseReason {
    fn from(code: u16) -> Self {
        Self::from_u16(code)
    }
}

impl From<DatagramCloseReason> for u16 {
    fn from(reason: DatagramCloseReason) -> Self {
        reason.as_u16()
    }
}
