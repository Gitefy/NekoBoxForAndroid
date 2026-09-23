//! Frozen protocol-domain numeric constants from VELA-SPEC-0.1-FROZEN.

pub const PROTOCOL_MAJOR: u8 = 1;
pub const MAX_NOISE_MESSAGE: usize = 65_535;
pub const VCP1_TAG_LEN: usize = 16;
pub const MIN_VELA_PLAINTEXT: usize = 4;
pub const MAX_VELA_PLAINTEXT: usize = 65_519;
pub const MIN_TRANSPORT_CIPHERTEXT: usize = 20;
pub const MAX_DATAGRAM_PAYLOAD: usize = 65_507;
pub const MAX_DNS_MESSAGE: usize = 65_507;
pub const MAX_DOMAIN_LEN: usize = 253;
pub const MAX_DOMAIN_LABEL_LEN: usize = 63;
