//! Canonical Vela endpoint codec.

use vela_proto::{DomainName, Endpoint, MAX_DOMAIN_LEN, Port};

use crate::cursor::Cursor;
use crate::error::WireError;

/// Decodes an [`Endpoint`] from the beginning of `input`.
///
/// Returns the decoded [`Endpoint`] and the exact number of bytes consumed.
///
/// # Errors
///
/// Returns:
/// - [`WireError::Truncated`] if `input` terminates before the endpoint structure is complete.
/// - [`WireError::InvalidEndpoint`] if the address type code is unassigned (not 0x01, 0x02, or 0x03).
/// - [`WireError::InvalidPort`] if the decoded port is zero.
/// - [`WireError::InvalidDomain`] if the domain length or canonical LDH/ASCII rules are violated.
pub fn decode_endpoint(input: &[u8]) -> Result<(Endpoint, usize), WireError> {
    let mut cursor = Cursor::new(input);
    let atyp = cursor.u8()?;

    let endpoint = match atyp {
        0x01 => {
            let addr_bytes = cursor.take(4)?;
            let mut address = [0u8; 4];
            address.copy_from_slice(addr_bytes);

            let port_raw = cursor.u16_be()?;
            let port = Port::new(port_raw).ok_or(WireError::InvalidPort)?;

            Endpoint::ipv4(address, port)
        }
        0x02 => {
            let addr_bytes = cursor.take(16)?;
            let mut address = [0u8; 16];
            address.copy_from_slice(addr_bytes);

            let port_raw = cursor.u16_be()?;
            let port = Port::new(port_raw).ok_or(WireError::InvalidPort)?;

            Endpoint::ipv6(address, port)
        }
        0x03 => {
            let name_len = cursor.u8()? as usize;
            if name_len == 0 || name_len > MAX_DOMAIN_LEN {
                return Err(WireError::InvalidDomain);
            }
            let name_bytes = cursor.take(name_len)?;
            if !name_bytes.is_ascii() {
                return Err(WireError::InvalidDomain);
            }
            let name_str = std::str::from_utf8(name_bytes).map_err(|_| WireError::InvalidDomain)?;
            let name =
                DomainName::new(name_str.to_owned()).map_err(|_| WireError::InvalidDomain)?;

            let port_raw = cursor.u16_be()?;
            let port = Port::new(port_raw).ok_or(WireError::InvalidPort)?;

            Endpoint::domain(name, port)
        }
        _ => return Err(WireError::InvalidEndpoint),
    };

    let consumed = input.len() - cursor.remaining();
    Ok((endpoint, consumed))
}

/// Encodes an [`Endpoint`] into canonical wire format, appending bytes to `out`.
///
/// # Errors
///
/// Returns [`WireError::InvalidDomain`] if a domain name exceeds maximum wire length.
pub fn encode_endpoint(endpoint: &Endpoint, out: &mut Vec<u8>) -> Result<(), WireError> {
    match endpoint {
        Endpoint::Ipv4 { address, port } => {
            out.push(0x01);
            out.extend_from_slice(address);
            out.extend_from_slice(&port.get().to_be_bytes());
        }
        Endpoint::Ipv6 { address, port } => {
            out.push(0x02);
            out.extend_from_slice(address);
            out.extend_from_slice(&port.get().to_be_bytes());
        }
        Endpoint::Domain { name, port } => {
            let s = name.as_str();
            if s.is_empty() || s.len() > MAX_DOMAIN_LEN || s.len() > 255 {
                return Err(WireError::InvalidDomain);
            }
            out.push(0x03);
            #[allow(clippy::cast_possible_truncation)]
            out.push(s.len() as u8);
            out.extend_from_slice(s.as_bytes());
            out.extend_from_slice(&port.get().to_be_bytes());
        }
    }
    Ok(())
}
