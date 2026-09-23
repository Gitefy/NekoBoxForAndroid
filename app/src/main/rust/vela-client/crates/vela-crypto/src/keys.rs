//! Type-safe, zeroized key material for the Vela cryptographic engine.
//!
//! Invariants:
//! - Private keys implement automatic zeroization on drop (`ZeroizeOnDrop`).
//! - `Debug` and `Display` for private keys never reveal key material.
//! - Private keys do **not** implement `Clone` or `Copy`.
//! - Public keys and channel-binding hashes implement `Clone`, `Copy`,
//!   `PartialEq`, `Eq`, `Hash`.
//! - No public ephemeral private key type exists; ephemerals are generated
//!   internally by the Noise engine via the OS CSPRNG.

use zeroize::{Zeroize, ZeroizeOnDrop};

macro_rules! private_key_type {
    ($(#[$meta:meta])* $name:ident) => {
        $(#[$meta])*
        ///
        /// Private keys are intentionally neither `Clone` nor `Copy`; the
        /// following compile-fail doc-test acts as a permanent sentinel:
        ///
        /// ```compile_fail
        /// use vela_crypto::keys::$name;
        /// let key = $name::new([0u8; 32]);
        /// let _copied = key.clone();
        /// ```
        #[derive(Zeroize, ZeroizeOnDrop)]
        pub struct $name([u8; 32]);

        impl $name {
            /// Constructs a private key from exactly 32 bytes.
            #[must_use]
            pub const fn new(bytes: [u8; 32]) -> Self {
                Self(bytes)
            }

            /// Returns the secret bytes.
            #[must_use]
            pub const fn secret_bytes(&self) -> &[u8; 32] {
                &self.0
            }
        }

        impl std::fmt::Debug for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str("[REDACTED]")
            }
        }

        impl std::fmt::Display for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str("[REDACTED]")
            }
        }
    };
}

macro_rules! public_value_type {
    ($(#[$meta:meta])* $name:ident) => {
        $(#[$meta])*
        #[derive(Clone, Copy, PartialEq, Eq, Hash)]
        pub struct $name([u8; 32]);

        impl $name {
            /// Constructs the value from exactly 32 bytes.
            #[must_use]
            pub const fn new(bytes: [u8; 32]) -> Self {
                Self(bytes)
            }

            /// Returns the raw 32 bytes.
            #[must_use]
            pub const fn as_bytes(&self) -> &[u8; 32] {
                &self.0
            }
        }

        impl std::fmt::Debug for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                write!(f, "{}({})", stringify!($name), crate::keys::hex_short(&self.0))
            }
        }
    };
}

private_key_type! {
    /// 32-byte X25519 private key for the server static identity.
    ServerStaticPrivateKey
}

impl ServerStaticPrivateKey {
    /// Generates a fresh server static keypair using the VCP-1 Noise profile.
    ///
    /// # Panics
    /// Panics if the Noise builder rejects the VCP-1 pattern string.
    #[must_use]
    pub fn generate_keypair() -> (Self, ServerStaticPublicKey) {
        let keypair = snow::Builder::new(
            "Noise_XK_25519_ChaChaPoly_SHA256"
                .parse()
                .expect("valid noise pattern"),
        )
        .generate_keypair()
        .expect("Noise keypair generation must succeed");
        let private: [u8; 32] = keypair
            .private
            .as_slice()
            .try_into()
            .expect("32-byte private");
        let public: [u8; 32] = keypair
            .public
            .as_slice()
            .try_into()
            .expect("32-byte public");
        (Self::new(private), ServerStaticPublicKey::new(public))
    }
}

private_key_type! {
    /// 32-byte X25519 private key for the client static identity.
    ClientStaticPrivateKey
}

impl ClientStaticPrivateKey {
    /// Generates a fresh client static keypair using the VCP-1 Noise profile.
    ///
    /// # Panics
    /// Panics if the Noise builder rejects the VCP-1 pattern string.
    #[must_use]
    pub fn generate_keypair() -> (Self, ClientStaticPublicKey) {
        let keypair = snow::Builder::new(
            "Noise_XK_25519_ChaChaPoly_SHA256"
                .parse()
                .expect("valid noise pattern"),
        )
        .generate_keypair()
        .expect("Noise keypair generation must succeed");
        let private: [u8; 32] = keypair
            .private
            .as_slice()
            .try_into()
            .expect("32-byte private");
        let public: [u8; 32] = keypair
            .public
            .as_slice()
            .try_into()
            .expect("32-byte public");
        (Self::new(private), ClientStaticPublicKey::new(public))
    }
}

public_value_type! {
    /// 32-byte X25519 public key for the server static identity.
    ServerStaticPublicKey
}

public_value_type! {
    /// 32-byte X25519 public key for the client static identity.
    ClientStaticPublicKey
}

public_value_type! {
    /// 32-byte final handshake hash used as immutable channel binding.
    HandshakeHash
}

/// Formats the first 8 bytes of a 32-byte value as hex for diagnostics.
pub(crate) fn hex_short(bytes: &[u8; 32]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(16);
    for byte in &bytes[..8] {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0F) as usize] as char);
    }
    out
}
