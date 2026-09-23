//! Canonical `DomainName`, `Port`, and `Endpoint` semantic domain types.

use core::num::NonZeroU16;
use std::fmt;

use crate::constants::{MAX_DOMAIN_LABEL_LEN, MAX_DOMAIN_LEN};

/// Errors encountered when constructing or validating a `DomainName`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DomainNameError {
    Empty,
    TooLong,
    LabelEmpty,
    LabelTooLong,
    NonAscii,
    Uppercase,
    TrailingDot,
    InvalidCharacter,
    EdgeHyphen,
}

impl fmt::Display for DomainNameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => write!(f, "domain name cannot be empty"),
            Self::TooLong => write!(f, "domain name exceeds 253 bytes"),
            Self::LabelEmpty => write!(f, "domain label cannot be empty"),
            Self::LabelTooLong => write!(f, "domain label exceeds 63 bytes"),
            Self::NonAscii => write!(f, "domain name must be ASCII"),
            Self::Uppercase => write!(f, "domain name must be lowercase"),
            Self::TrailingDot => write!(f, "domain name must not have a trailing dot"),
            Self::InvalidCharacter => write!(f, "domain label contains invalid character"),
            Self::EdgeHyphen => write!(f, "domain label cannot start or end with a hyphen"),
        }
    }
}

impl std::error::Error for DomainNameError {}

/// A validated, canonical lowercase DNS A-label / LDH domain name.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DomainName(String);

impl DomainName {
    /// Validates and constructs a canonical `DomainName`.
    ///
    /// Rules:
    /// - 1..=253 bytes total.
    /// - ASCII only.
    /// - Lowercase only (A-Z rejected).
    /// - No trailing dot.
    /// - Labels separated by '.', each 1..=63 bytes.
    /// - Label characters restricted to [a-z0-9-].
    /// - Labels must not start or end with '-'.
    ///
    /// # Errors
    ///
    /// Returns a [`DomainNameError`] if the domain string violates any of the canonical validation rules.
    pub fn new(name: String) -> Result<Self, DomainNameError> {
        if name.is_empty() {
            return Err(DomainNameError::Empty);
        }
        if name.len() > MAX_DOMAIN_LEN {
            return Err(DomainNameError::TooLong);
        }
        if !name.is_ascii() {
            return Err(DomainNameError::NonAscii);
        }
        if name.bytes().any(|b| b.is_ascii_uppercase()) {
            return Err(DomainNameError::Uppercase);
        }
        if name.ends_with('.') {
            return Err(DomainNameError::TrailingDot);
        }

        for label in name.split('.') {
            if label.is_empty() {
                return Err(DomainNameError::LabelEmpty);
            }
            if label.len() > MAX_DOMAIN_LABEL_LEN {
                return Err(DomainNameError::LabelTooLong);
            }
            if label.starts_with('-') || label.ends_with('-') {
                return Err(DomainNameError::EdgeHyphen);
            }
            for b in label.bytes() {
                if !matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'-') {
                    return Err(DomainNameError::InvalidCharacter);
                }
            }
        }

        Ok(Self(name))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    #[must_use]
    pub fn into_string(self) -> String {
        self.0
    }
}

impl fmt::Display for DomainName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl TryFrom<String> for DomainName {
    type Error = DomainNameError;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::new(value)
    }
}

impl TryFrom<&str> for DomainName {
    type Error = DomainNameError;

    fn try_from(value: &str) -> Result<Self, Self::Error> {
        Self::new(value.to_owned())
    }
}

/// A non-zero 16-bit network port (1..=65535).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Port(NonZeroU16);

impl Port {
    #[must_use]
    pub const fn new(value: u16) -> Option<Self> {
        match NonZeroU16::new(value) {
            Some(inner) => Some(Self(inner)),
            None => None,
        }
    }

    #[must_use]
    pub const fn get(self) -> u16 {
        self.0.get()
    }

    #[must_use]
    pub const fn as_nonzero(self) -> NonZeroU16 {
        self.0
    }
}

impl TryFrom<u16> for Port {
    type Error = ();

    fn try_from(value: u16) -> Result<Self, Self::Error> {
        Self::new(value).ok_or(())
    }
}

impl From<Port> for u16 {
    fn from(port: Port) -> Self {
        port.get()
    }
}

impl fmt::Display for Port {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// A strongly typed network endpoint destination.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Endpoint {
    Ipv4 { address: [u8; 4], port: Port },
    Ipv6 { address: [u8; 16], port: Port },
    Domain { name: DomainName, port: Port },
}

impl Endpoint {
    #[must_use]
    pub const fn ipv4(address: [u8; 4], port: Port) -> Self {
        Self::Ipv4 { address, port }
    }

    #[must_use]
    pub const fn ipv6(address: [u8; 16], port: Port) -> Self {
        Self::Ipv6 { address, port }
    }

    #[must_use]
    pub fn domain(name: DomainName, port: Port) -> Self {
        Self::Domain { name, port }
    }

    #[must_use]
    pub fn try_ipv4(address: [u8; 4], port: u16) -> Option<Self> {
        Port::new(port).map(|p| Self::ipv4(address, p))
    }

    #[must_use]
    pub fn try_ipv6(address: [u8; 16], port: u16) -> Option<Self> {
        Port::new(port).map(|p| Self::ipv6(address, p))
    }

    #[must_use]
    pub fn try_domain(name: DomainName, port: u16) -> Option<Self> {
        Port::new(port).map(|p| Self::domain(name, p))
    }

    #[must_use]
    pub const fn port(&self) -> Port {
        match self {
            Self::Ipv4 { port, .. } | Self::Ipv6 { port, .. } | Self::Domain { port, .. } => *port,
        }
    }
}
