#![forbid(unsafe_code)]
//! Exclusive ownership boundary for DIRECT/TUNNEL/BLOCK policy decisions.
//!
//! `vela-policy` does not perform I/O, cryptography, or session state
//! management. It answers the question: "given an authenticated client identity
//! and a requested target, what action is permitted?"

use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use vela_proto::Endpoint;

/// The action a policy grants for a given request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Action {
    /// Allow the request to proceed directly to the target.
    Direct,
    /// Allow the request only through a tunnel/proxy path.
    Tunnel,
    /// Deny the request.
    Block,
}

/// The result of a policy evaluation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Decision {
    /// The action the caller must take.
    pub action: Action,
    /// An optional human-readable reason for diagnostics.
    pub reason: Option<&'static str>,
}

impl Decision {
    /// Shorthand for an allowed direct decision.
    #[must_use]
    pub const fn direct() -> Self {
        Self {
            action: Action::Direct,
            reason: None,
        }
    }

    /// Shorthand for a blocked decision with a reason.
    #[must_use]
    pub const fn block(reason: &'static str) -> Self {
        Self {
            action: Action::Block,
            reason: Some(reason),
        }
    }
}

/// Policy evaluation errors.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PolicyError {
    /// The policy configuration is invalid.
    InvalidConfiguration(&'static str),
}

impl std::fmt::Display for PolicyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidConfiguration(msg) => write!(f, "invalid policy configuration: {msg}"),
        }
    }
}

impl std::error::Error for PolicyError {}

/// The policy trait. Implementations decide whether a client is authorized and
/// whether individual targets are reachable.
pub trait Policy {
    /// Authorize a client static public key before session establishment.
    fn authorize(&self, client_public_key: &[u8; 32]) -> Decision;

    /// Decide whether a stream open toward `endpoint` is permitted.
    fn stream(&self, client_public_key: &[u8; 32], endpoint: &Endpoint) -> Decision;

    /// Decide whether a datagram context open toward `endpoint` is permitted.
    fn datagram(&self, client_public_key: &[u8; 32], endpoint: &Endpoint) -> Decision;
}

/// A simple static policy: explicit allow-lists plus default-deny.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct StaticPolicy {
    allowed_clients: HashSet<[u8; 32]>,
    allowed_stream_endpoints: HashSet<Endpoint>,
    allowed_datagram_endpoints: HashSet<Endpoint>,
    default_reason: &'static str,
}

impl StaticPolicy {
    /// Creates an empty static policy that blocks everything by default.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Allows the given client public key to establish a session.
    #[must_use]
    pub fn allow_client(mut self, key: [u8; 32]) -> Self {
        self.allowed_clients.insert(key);
        self
    }

    /// Allows stream opens to the given endpoint.
    #[must_use]
    pub fn allow_stream(mut self, endpoint: Endpoint) -> Self {
        self.allowed_stream_endpoints.insert(endpoint);
        self
    }

    /// Allows datagram context opens to the given endpoint.
    #[must_use]
    pub fn allow_datagram(mut self, endpoint: Endpoint) -> Self {
        self.allowed_datagram_endpoints.insert(endpoint);
        self
    }

    /// Sets the reason string attached to default-deny decisions.
    #[must_use]
    pub fn with_default_reason(mut self, reason: &'static str) -> Self {
        self.default_reason = reason;
        self
    }

    fn default_decision(&self) -> Decision {
        Decision::block(self.default_reason)
    }
}

impl Policy for StaticPolicy {
    fn authorize(&self, client_public_key: &[u8; 32]) -> Decision {
        if self.allowed_clients.contains(client_public_key) {
            Decision::direct()
        } else {
            Decision::block("client not in allow-list")
        }
    }

    fn stream(&self, _client_public_key: &[u8; 32], endpoint: &Endpoint) -> Decision {
        if self.allowed_stream_endpoints.contains(endpoint) {
            Decision::direct()
        } else {
            self.default_decision()
        }
    }

    fn datagram(&self, _client_public_key: &[u8; 32], endpoint: &Endpoint) -> Decision {
        if self.allowed_datagram_endpoints.contains(endpoint) {
            Decision::direct()
        } else {
            self.default_decision()
        }
    }
}

/// Process-wide fail-closed latch (frozen spec §16).
///
/// Once tripped, wrapped policies MUST NOT return [`Action::Direct`].
#[derive(Debug, Clone)]
pub struct FailClosed {
    tripped: Arc<AtomicBool>,
}

impl Default for FailClosed {
    fn default() -> Self {
        Self::new()
    }
}

impl FailClosed {
    /// Creates a clear latch.
    #[must_use]
    pub fn new() -> Self {
        Self {
            tripped: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Trips the latch. Subsequent [`Self::is_tripped`] calls return `true`.
    pub fn trip(&self) {
        self.tripped.store(true, Ordering::SeqCst);
    }

    /// Returns whether the latch has been tripped.
    #[must_use]
    pub fn is_tripped(&self) -> bool {
        self.tripped.load(Ordering::SeqCst)
    }
}

/// Policy wrapper that blocks every decision after [`FailClosed::trip`].
#[derive(Debug, Clone)]
pub struct FailClosedPolicy<P> {
    inner: P,
    latch: FailClosed,
}

impl<P> FailClosedPolicy<P> {
    /// Wraps `inner` with a fresh latch.
    #[must_use]
    pub fn wrap(inner: P) -> Self {
        Self {
            inner,
            latch: FailClosed::new(),
        }
    }

    /// Wraps `inner` with a shared latch.
    #[must_use]
    pub fn with_latch(inner: P, latch: FailClosed) -> Self {
        Self { inner, latch }
    }

    /// Returns a clone of the latch so callers can trip it on terminal errors.
    #[must_use]
    pub fn latch(&self) -> FailClosed {
        self.latch.clone()
    }
}

impl<P: Policy> Policy for FailClosedPolicy<P> {
    fn authorize(&self, client_public_key: &[u8; 32]) -> Decision {
        if self.latch.is_tripped() {
            return Decision::block("fail-closed latch");
        }
        self.inner.authorize(client_public_key)
    }

    fn stream(&self, client_public_key: &[u8; 32], endpoint: &Endpoint) -> Decision {
        if self.latch.is_tripped() {
            return Decision::block("fail-closed latch");
        }
        self.inner.stream(client_public_key, endpoint)
    }

    fn datagram(&self, client_public_key: &[u8; 32], endpoint: &Endpoint) -> Decision {
        if self.latch.is_tripped() {
            return Decision::block("fail-closed latch");
        }
        self.inner.datagram(client_public_key, endpoint)
    }
}

/// Marker for the exclusive Vela policy ownership boundary.
#[derive(Debug, Default, Clone, Copy)]
pub struct PolicyBoundary;
