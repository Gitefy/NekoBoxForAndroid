//! Nonzero identifier types for streams, datagram contexts, and DNS requests.

use core::num::NonZeroU32;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct StreamId(NonZeroU32);

impl StreamId {
    #[must_use]
    pub const fn new(value: u32) -> Option<Self> {
        match NonZeroU32::new(value) {
            Some(inner) => Some(Self(inner)),
            None => None,
        }
    }

    #[must_use]
    pub const fn get(self) -> u32 {
        self.0.get()
    }

    #[must_use]
    pub const fn as_nonzero(self) -> NonZeroU32 {
        self.0
    }
}

impl TryFrom<u32> for StreamId {
    type Error = ();

    fn try_from(value: u32) -> Result<Self, Self::Error> {
        Self::new(value).ok_or(())
    }
}

impl From<StreamId> for u32 {
    fn from(id: StreamId) -> Self {
        id.get()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ContextId(NonZeroU32);

impl ContextId {
    #[must_use]
    pub const fn new(value: u32) -> Option<Self> {
        match NonZeroU32::new(value) {
            Some(inner) => Some(Self(inner)),
            None => None,
        }
    }

    #[must_use]
    pub const fn get(self) -> u32 {
        self.0.get()
    }

    #[must_use]
    pub const fn as_nonzero(self) -> NonZeroU32 {
        self.0
    }
}

impl TryFrom<u32> for ContextId {
    type Error = ();

    fn try_from(value: u32) -> Result<Self, Self::Error> {
        Self::new(value).ok_or(())
    }
}

impl From<ContextId> for u32 {
    fn from(id: ContextId) -> Self {
        id.get()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DnsRequestId(NonZeroU32);

impl DnsRequestId {
    #[must_use]
    pub const fn new(value: u32) -> Option<Self> {
        match NonZeroU32::new(value) {
            Some(inner) => Some(Self(inner)),
            None => None,
        }
    }

    #[must_use]
    pub const fn get(self) -> u32 {
        self.0.get()
    }

    #[must_use]
    pub const fn as_nonzero(self) -> NonZeroU32 {
        self.0
    }
}

impl TryFrom<u32> for DnsRequestId {
    type Error = ();

    fn try_from(value: u32) -> Result<Self, Self::Error> {
        Self::new(value).ok_or(())
    }
}

impl From<DnsRequestId> for u32 {
    fn from(id: DnsRequestId) -> Self {
        id.get()
    }
}
