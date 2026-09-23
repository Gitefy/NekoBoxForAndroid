//! Vela DNS request lifecycle (spec §13).
//!
//! Invariants:
//! - `DNSRequestID` is nonzero, client-allocated, monotonically increasing
//!   within the session, and unique while outstanding. Spec §13 defines NO
//!   parity rule for DNS request IDs (unlike `StreamID`/`ContextID`).
//! - A `DNS_RESPONSE` must match an outstanding request.
//! - `DNS_CANCEL` cancels an outstanding request: the client releases its
//!   allocation; the server releases its inbound tracking.
//! - DNS messages are bounded by the frame constructor (≤ 65,507 bytes);
//!   resource-record semantics are outside this layer.

use std::collections::HashSet;

use vela_proto::DnsRequestId;

use crate::error::SessionError;
use crate::ids::DnsRequestIdAllocator;

/// Table of DNS requests for a session endpoint.
///
/// The client allocates identifiers via the allocator; the server tracks the
/// inbound queries it must answer. Both directions enforce the
/// outstanding-once and monotonicity invariants.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsTable {
    ids: DnsRequestIdAllocator,
    inbound: HashSet<u32>,
    last_inbound: u32,
}

impl DnsTable {
    /// Creates an empty DNS request table.
    #[must_use]
    pub fn new() -> Self {
        Self {
            ids: DnsRequestIdAllocator::new(),
            inbound: HashSet::new(),
            last_inbound: 0,
        }
    }

    /// Client: registers a `DNS_QUERY` and returns its allocated request ID.
    ///
    /// # Errors
    /// [`SessionError::ResourceLimit`] when the identifier space is exhausted.
    pub fn query(&mut self) -> Result<DnsRequestId, SessionError> {
        self.ids.allocate()
    }

    /// Returns `true` while the request is awaiting a response.
    #[must_use]
    pub fn is_outstanding(&self, id: DnsRequestId) -> bool {
        self.ids.is_outstanding(id)
    }

    /// Client: processes a `DNS_RESPONSE` for a matching outstanding request.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when no matching request is
    /// outstanding.
    pub fn response(&mut self, id: DnsRequestId) -> Result<(), SessionError> {
        self.ids.complete(id)
    }

    /// Client: applies `DNS_CANCEL`, releasing the outstanding request.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when no matching request is
    /// outstanding.
    pub fn cancel(&mut self, id: DnsRequestId) -> Result<(), SessionError> {
        self.ids.complete(id)
    }

    /// Server: tracks an inbound `DNS_QUERY` so its response can be validated.
    ///
    /// Request IDs must strictly increase within the session and are unique
    /// while outstanding (spec §13).
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the identifier is not
    /// increasing or duplicates a tracked request.
    pub fn track_inbound(&mut self, id: DnsRequestId) -> Result<(), SessionError> {
        let value = id.get();
        if value <= self.last_inbound {
            return Err(SessionError::ProtocolViolation(
                "dns request id is not increasing",
            ));
        }
        if !self.inbound.insert(value) {
            return Err(SessionError::ProtocolViolation(
                "dns request id is not outstanding",
            ));
        }
        self.last_inbound = value;
        Ok(())
    }

    /// Server: releases an inbound query once the response is sent.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the identifier is not tracked.
    pub fn respond(&mut self, id: DnsRequestId) -> Result<(), SessionError> {
        if self.inbound.remove(&id.get()) {
            Ok(())
        } else {
            Err(SessionError::ProtocolViolation(
                "dns request id is not outstanding",
            ))
        }
    }

    /// Server: applies an inbound `DNS_CANCEL`, releasing the tracked query.
    ///
    /// # Errors
    /// [`SessionError::ProtocolViolation`] when the identifier is not tracked.
    pub fn cancel_inbound(&mut self, id: DnsRequestId) -> Result<(), SessionError> {
        if self.inbound.remove(&id.get()) {
            Ok(())
        } else {
            Err(SessionError::ProtocolViolation(
                "dns request id is not outstanding",
            ))
        }
    }
}

impl Default for DnsTable {
    fn default() -> Self {
        Self::new()
    }
}
