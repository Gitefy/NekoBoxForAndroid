//! SOCKS5 proxy server for Vela client.
//!
//! Exposes a local SOCKS5 listener (RFC 1928, NO AUTH) that transparently
//! multiplexes local application connections over an authenticated, encrypted
//! Vela protocol session to a remote Vela server.

use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{ErrorKind, Read, Write};
use std::net::UdpSocket;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use vela_crypto::keys::{ClientStaticPrivateKey, ServerStaticPublicKey};
use vela_proto::{
    ContextId, DatagramCloseReason, DomainName, Endpoint, MAX_DATAGRAM_PAYLOAD, MAX_DOMAIN_LEN,
    OpenResultStatus, Port, StreamId, StreamResetCode,
};
use vela_session::{Role, SessionConfig};
use vela_transport::TransportError;
use vela_transport::tcp::{ConnectConfig, connect_client};

pub const PROXY_CONN_WINDOW: u64 = 16 * 1024 * 1024; // 16 MB
pub const PROXY_STREAM_WINDOW: u64 = 8 * 1024 * 1024; // 8 MB
pub const MAX_CONCURRENT_STREAMS: u32 = 128;
const PUMP_POLL_TIMEOUT: Duration = Duration::from_millis(1);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Socks5Request {
    Connect(Endpoint),
    UdpAssociate,
}

// Connection credit tracks released receive buffers, including cancelled writes.
// Ownership makes every buffer return credit exactly once, even on channel failure.
struct ReceivedChunk {
    data: Vec<u8>,
    conn_consumed: Arc<AtomicU64>,
}

impl Drop for ReceivedChunk {
    fn drop(&mut self) {
        self.conn_consumed
            .fetch_add(self.data.len() as u64, Ordering::Relaxed);
    }
}

type OpenReady = (u64, StreamId, mpsc::Receiver<ReceivedChunk>, Arc<AtomicU64>);
type OpenReply = mpsc::Sender<Result<OpenReady, String>>;

struct StreamEntry {
    cancel_sock: TcpStream,
    data_rx: mpsc::Receiver<Vec<u8>>,
    app_writer_tx: Option<mpsc::Sender<ReceivedChunk>>,
    consumed: Arc<AtomicU64>,
    input_drained: bool,
    local_fin: bool,
    remote_fin: bool,
    open_reply: Option<(OpenReply, mpsc::Receiver<ReceivedChunk>)>,
}

enum SessionCmd {
    Open {
        endpoint: Endpoint,
        resp_tx: OpenReply,
        cancel_sock: TcpStream,
        data_rx: mpsc::Receiver<Vec<u8>>,
    },
    Fin(u64, StreamId),
    Reset(u64, StreamId),
    UdpAssociate {
        control_sock: TcpStream,
        udp_socket: UdpSocket,
        resp_tx: mpsc::Sender<(u64, SocketAddr)>,
    },
    UdpPacket(u64, SocketAddr, Endpoint, Vec<u8>),
    CloseUdp(u64),
}

struct UdpAssociation {
    control_sock: TcpStream,
    socket: UdpSocket,
    peer_ip: std::net::IpAddr,
    client_addr: Option<SocketAddr>,
}

struct UdpRoute {
    association_id: u64,
    endpoint: Endpoint,
    active: bool,
    pending: VecDeque<Vec<u8>>,
}

/// SOCKS5 protocol handshake and request parser.
///
/// # Errors
/// Returns an error for invalid requests or socket I/O failure.
pub fn handle_socks5_handshake(mut stream: &TcpStream) -> Result<Socks5Request, String> {
    // 1. Version & authentication methods
    let mut header = [0u8; 2];
    stream
        .read_exact(&mut header)
        .map_err(|e| format!("read greeting failed: {e}"))?;

    if header[0] != 0x05 {
        return Err(format!("unsupported SOCKS version: {}", header[0]));
    }

    let num_methods = header[1] as usize;
    let mut methods = vec![0u8; num_methods];
    stream
        .read_exact(&mut methods)
        .map_err(|e| format!("read methods failed: {e}"))?;

    if !methods.contains(&0x00) {
        stream.write_all(&[0x05, 0xff]).map_err(|e| e.to_string())?;
        return Err("no acceptable authentication method".to_string());
    }

    // Respond with 0x05 0x00 (No authentication required)
    stream
        .write_all(&[0x05, 0x00])
        .map_err(|e| format!("write greeting failed: {e}"))?;

    // 2. Request details
    let mut req_header = [0u8; 4];
    stream
        .read_exact(&mut req_header)
        .map_err(|e| format!("read request failed: {e}"))?;

    if req_header[0] != 0x05 || req_header[2] != 0 {
        return Err("invalid version in request".to_string());
    }
    if req_header[1] != 0x01 && req_header[1] != 0x03 {
        return Err(format!("unsupported command: {}", req_header[1]));
    }

    let atyp = req_header[3];
    if req_header[1] == 0x03 {
        discard_socks5_address(stream, atyp)?;
        return Ok(Socks5Request::UdpAssociate);
    }
    let endpoint = match atyp {
        0x01 => {
            // IPv4: 4 bytes
            let mut ip = [0u8; 4];
            stream
                .read_exact(&mut ip)
                .map_err(|e| format!("read ipv4 failed: {e}"))?;
            let mut port_buf = [0u8; 2];
            stream
                .read_exact(&mut port_buf)
                .map_err(|e| format!("read port failed: {e}"))?;
            let port_num = u16::from_be_bytes(port_buf);
            let port = Port::new(port_num).ok_or("port cannot be 0")?;
            Endpoint::Ipv4 { address: ip, port }
        }
        0x03 => {
            // Domain: 1 byte len + name
            let mut len_buf = [0u8; 1];
            stream
                .read_exact(&mut len_buf)
                .map_err(|e| format!("read domain len failed: {e}"))?;
            let len = len_buf[0] as usize;
            let mut name_buf = vec![0u8; len];
            stream
                .read_exact(&mut name_buf)
                .map_err(|e| format!("read domain name failed: {e}"))?;
            let mut port_buf = [0u8; 2];
            stream
                .read_exact(&mut port_buf)
                .map_err(|e| format!("read port failed: {e}"))?;
            let port_num = u16::from_be_bytes(port_buf);
            let port = Port::new(port_num).ok_or("port cannot be 0")?;
            let name_str = String::from_utf8(name_buf)
                .map_err(|_| "domain name must be valid utf8".to_string())?
                .to_ascii_lowercase();
            let domain =
                DomainName::new(name_str).map_err(|e| format!("invalid domain name: {e}"))?;
            Endpoint::Domain { name: domain, port }
        }
        0x04 => {
            // IPv6: 16 bytes
            let mut ip = [0u8; 16];
            stream
                .read_exact(&mut ip)
                .map_err(|e| format!("read ipv6 failed: {e}"))?;
            let mut port_buf = [0u8; 2];
            stream
                .read_exact(&mut port_buf)
                .map_err(|e| format!("read port failed: {e}"))?;
            let port_num = u16::from_be_bytes(port_buf);
            let port = Port::new(port_num).ok_or("port cannot be 0")?;
            Endpoint::Ipv6 { address: ip, port }
        }
        _ => return Err(format!("unsupported ATYP: {atyp}")),
    };

    Ok(Socks5Request::Connect(endpoint))
}

fn discard_socks5_address(mut stream: &TcpStream, atyp: u8) -> Result<(), String> {
    match atyp {
        0x01 => {
            let mut address = [0; 4];
            stream.read_exact(&mut address).map_err(|e| e.to_string())?;
        }
        0x03 => {
            let mut len = [0; 1];
            stream.read_exact(&mut len).map_err(|e| e.to_string())?;
            if len[0] == 0 {
                return Err("SOCKS5 UDP bind domain cannot be empty".into());
            }
            let mut address = vec![0; usize::from(len[0])];
            stream.read_exact(&mut address).map_err(|e| e.to_string())?;
            std::str::from_utf8(&address).map_err(|_| "invalid SOCKS5 UDP bind domain")?;
        }
        0x04 => {
            let mut address = [0; 16];
            stream.read_exact(&mut address).map_err(|e| e.to_string())?;
        }
        _ => return Err(format!("unsupported SOCKS5 address type: {atyp}")),
    }
    let mut port = [0; 2];
    stream.read_exact(&mut port).map_err(|e| e.to_string())?;
    Ok(())
}

/// Sends SOCKS5 success reply.
///
/// # Errors
/// Returns any socket write failure.
pub fn send_socks5_success(mut stream: &TcpStream) -> std::io::Result<()> {
    // 0x05 0x00(success) 0x00(rsv) 0x01(ipv4) 0,0,0,0 0,0
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])?;
    stream.flush()
}

fn send_socks5_udp_success(stream: &TcpStream, address: SocketAddr) -> std::io::Result<()> {
    let mut reply = vec![0x05, 0x00, 0x00];
    match address {
        SocketAddr::V4(addr) => {
            reply.push(0x01);
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            reply.push(0x04);
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    let mut stream = stream;
    stream.write_all(&reply)?;
    stream.flush()
}

/// Sends SOCKS5 error reply.
///
/// # Errors
/// Returns any socket write failure.
pub fn send_socks5_error(mut stream: &TcpStream, err_code: u8) -> std::io::Result<()> {
    stream.write_all(&[
        0x05, err_code, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ])?;
    stream.flush()
}

/// Decodes one RFC 1928 UDP datagram, rejecting fragmentation and malformed addresses.
///
/// # Errors
/// Returns an error for a nonzero reserved/fragment field, invalid address, or zero port.
pub fn decode_udp_datagram(packet: &[u8]) -> Result<(Endpoint, Vec<u8>), String> {
    if packet.len() < 4 || packet[..2] != [0, 0] {
        return Err("invalid SOCKS5 UDP reserved field".into());
    }
    if packet[2] != 0 {
        return Err("fragmented SOCKS5 UDP datagrams are unsupported".into());
    }

    let mut offset = 4;
    let endpoint = match packet[3] {
        0x01 => {
            let address = take_array::<4>(packet, &mut offset)?;
            let port = take_port(packet, &mut offset)?;
            Endpoint::Ipv4 { address, port }
        }
        0x03 => {
            let len = *packet
                .get(offset)
                .ok_or_else(|| "truncated SOCKS5 UDP domain length".to_string())?
                as usize;
            offset += 1;
            if len == 0 || len > MAX_DOMAIN_LEN {
                return Err("invalid SOCKS5 UDP domain length".into());
            }
            let bytes = take_bytes(packet, &mut offset, len)?;
            let name = String::from_utf8(bytes.to_vec())
                .map_err(|_| "SOCKS5 UDP domain is not UTF-8".to_string())?
                .to_ascii_lowercase();
            let name = DomainName::new(name).map_err(|error| format!("invalid domain: {error}"))?;
            let port = take_port(packet, &mut offset)?;
            Endpoint::Domain { name, port }
        }
        0x04 => {
            let address = take_array::<16>(packet, &mut offset)?;
            let port = take_port(packet, &mut offset)?;
            Endpoint::Ipv6 { address, port }
        }
        atyp => return Err(format!("unsupported SOCKS5 UDP address type: {atyp}")),
    };

    let payload = packet
        .get(offset..)
        .ok_or_else(|| "truncated SOCKS5 UDP payload".to_string())?;
    if payload.len() > MAX_DATAGRAM_PAYLOAD {
        return Err("SOCKS5 UDP payload exceeds Vela limit".into());
    }
    Ok((endpoint, payload.to_vec()))
}

/// Encodes a Vela endpoint and payload as an unfragmented SOCKS5 UDP datagram.
///
/// # Errors
/// Returns an error when the payload or domain exceeds protocol bounds.
pub fn encode_udp_datagram(endpoint: &Endpoint, payload: &[u8]) -> Result<Vec<u8>, String> {
    if payload.len() > MAX_DATAGRAM_PAYLOAD {
        return Err("SOCKS5 UDP payload exceeds Vela limit".into());
    }
    let mut packet = vec![0, 0, 0];
    match endpoint {
        Endpoint::Ipv4 { address, port } => {
            packet.push(0x01);
            packet.extend_from_slice(address);
            packet.extend_from_slice(&port.get().to_be_bytes());
        }
        Endpoint::Domain { name, port } => {
            let bytes = name.as_str().as_bytes();
            if bytes.is_empty() || bytes.len() > MAX_DOMAIN_LEN {
                return Err("invalid SOCKS5 UDP domain length".into());
            }
            packet.push(0x03);
            packet.push(bytes.len() as u8);
            packet.extend_from_slice(bytes);
            packet.extend_from_slice(&port.get().to_be_bytes());
        }
        Endpoint::Ipv6 { address, port } => {
            packet.push(0x04);
            packet.extend_from_slice(address);
            packet.extend_from_slice(&port.get().to_be_bytes());
        }
    }
    packet.extend_from_slice(payload);
    Ok(packet)
}

fn take_bytes<'a>(packet: &'a [u8], offset: &mut usize, len: usize) -> Result<&'a [u8], String> {
    let end = offset
        .checked_add(len)
        .ok_or_else(|| "SOCKS5 UDP address length overflow".to_string())?;
    let bytes = packet
        .get(*offset..end)
        .ok_or_else(|| "truncated SOCKS5 UDP address".to_string())?;
    *offset = end;
    Ok(bytes)
}

fn take_array<const N: usize>(packet: &[u8], offset: &mut usize) -> Result<[u8; N], String> {
    take_bytes(packet, offset, N)?
        .try_into()
        .map_err(|_| "invalid SOCKS5 UDP address length".into())
}

fn take_port(packet: &[u8], offset: &mut usize) -> Result<Port, String> {
    let bytes = take_array::<2>(packet, offset)?;
    Port::new(u16::from_be_bytes(bytes)).ok_or_else(|| "SOCKS5 UDP port cannot be zero".into())
}

/// Runs a SOCKS5 proxy server that bridges connections to a remote Vela server.
/// # Errors
/// Returns bind, handshake, or terminal transport failures.
// Keep the single-owner event loop and the existing owned-key API together.
#[allow(clippy::too_many_lines, clippy::needless_pass_by_value)]
pub fn run_socks5_proxy(
    listen_addr: SocketAddr,
    server_addr: SocketAddr,
    client_static: ClientStaticPrivateKey,
    server_static: ServerStaticPublicKey,
) -> Result<(), TransportError> {
    let listener = TcpListener::bind(listen_addr)?;
    println!("[vela] SOCKS5 proxy listening on {listen_addr}");
    println!("[vela] Establishing Vela tunnel to {server_addr}...");

    let connect_config = ConnectConfig {
        server_addr,
        connect_timeout: Duration::from_secs(10),
        handshake_timeout: Duration::from_secs(10),
    };
    let session_config = SessionConfig {
        role: Role::Client,
        initial_receive_credit: PROXY_CONN_WINDOW,
        max_concurrent_streams: MAX_CONCURRENT_STREAMS,
    };

    let mut driver = connect_client(
        &connect_config,
        &client_static,
        &server_static,
        session_config,
    )?;
    driver.enable_traffic_morphing(true);
    println!("[vela] Established authenticated Vela session to {server_addr}");

    let _ = driver
        .stream_mut()
        .set_read_timeout(Some(PUMP_POLL_TIMEOUT));
    let _ = driver.stream_mut().set_nodelay(true);
    driver.set_defer_flush(true);

    let (cmd_tx, cmd_rx) = mpsc::sync_channel::<SessionCmd>(256);

    let mut streams: HashMap<StreamId, StreamEntry> = HashMap::new();
    let mut udp_associations: HashMap<u64, UdpAssociation> = HashMap::new();
    let mut udp_routes: HashMap<ContextId, UdpRoute> = HashMap::new();
    let mut next_association_id = 1u64;

    // Spawn SOCKS5 client acceptor thread
    let cmd_tx_accept = cmd_tx.clone();
    thread::spawn(move || {
        for sock in listener.incoming() {
            let sock = match sock {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("[vela] SOCKS accept error: {e}");
                    continue;
                }
            };
            let _ = sock.set_nodelay(true);

            let cmd_tx_stream = cmd_tx_accept.clone();
            thread::spawn(move || {
                let request = match handle_socks5_handshake(&sock) {
                    Ok(request) => request,
                    Err(e) => {
                        eprintln!("[vela] SOCKS handshake error: {e}");
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    }
                };

                if matches!(&request, Socks5Request::UdpAssociate) {
                    let Ok(peer) = sock.peer_addr() else {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    };
                    let bind_addr = if peer.is_ipv4() {
                        "127.0.0.1:0"
                    } else {
                        "[::1]:0"
                    };
                    let Ok(udp_socket) = UdpSocket::bind(bind_addr) else {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    };
                    let _ = udp_socket.set_read_timeout(Some(Duration::from_millis(100)));
                    let Ok(reader_socket) = udp_socket.try_clone() else {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    };
                    let Ok(control_sock) = sock.try_clone() else {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    };
                    let (resp_tx, resp_rx) = mpsc::channel();
                    if cmd_tx_stream
                        .send(SessionCmd::UdpAssociate {
                            control_sock,
                            udp_socket,
                            resp_tx,
                        })
                        .is_err()
                    {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    }
                    let Ok((association_id, bound)) = resp_rx.recv() else {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    };
                    if send_socks5_udp_success(&sock, bound).is_err() {
                        let _ = cmd_tx_stream.send(SessionCmd::CloseUdp(association_id));
                        return;
                    }

                    let udp_cmd = cmd_tx_stream.clone();
                    thread::spawn(move || {
                        let mut buf = vec![0u8; 65_507];
                        loop {
                            match reader_socket.recv_from(&mut buf) {
                                Ok((size, source)) if source.ip().is_loopback() => {
                                    if let Ok((endpoint, payload)) =
                                        decode_udp_datagram(&buf[..size])
                                    {
                                        if udp_cmd
                                            .try_send(SessionCmd::UdpPacket(
                                                association_id,
                                                source,
                                                endpoint,
                                                payload,
                                            ))
                                            .is_err()
                                        {
                                            // Drop rather than allocate unbounded memory under pressure.
                                        }
                                    }
                                }
                                Ok(_) => {}
                                Err(error)
                                    if matches!(
                                        error.kind(),
                                        ErrorKind::TimedOut | ErrorKind::WouldBlock
                                    ) => {}
                                Err(_) => break,
                            }
                        }
                    });

                    let close_cmd = cmd_tx_stream.clone();
                    let mut monitor_sock = sock;
                    thread::spawn(move || {
                        let _ = monitor_sock.set_read_timeout(Some(Duration::from_millis(500)));
                        let mut byte = [0u8; 1];
                        loop {
                            match monitor_sock.read(&mut byte) {
                                Ok(_) => break,
                                Err(error)
                                    if matches!(
                                        error.kind(),
                                        ErrorKind::TimedOut | ErrorKind::WouldBlock
                                    ) =>
                                {
                                    continue;
                                }
                                Err(_) => break,
                            }
                        }
                        let _ = close_cmd.send(SessionCmd::CloseUdp(association_id));
                    });
                    return;
                }

                let Socks5Request::Connect(endpoint) = request else {
                    unreachable!("UDP association handled above");
                };

                let Ok(cancel_sock) = sock.try_clone() else {
                    return;
                };
                let (resp_tx, resp_rx) = mpsc::channel();
                let (app_data_tx, app_data_rx) = mpsc::sync_channel(8);

                if cmd_tx_stream
                    .send(SessionCmd::Open {
                        endpoint,
                        resp_tx,
                        cancel_sock,
                        data_rx: app_data_rx,
                    })
                    .is_err()
                {
                    let _ = send_socks5_error(&sock, 0x01);
                    return;
                }

                let (generation, stream_id, server_data_rx, stream_consumed) = match resp_rx.recv()
                {
                    Ok(Ok(ready)) => ready,
                    Ok(Err(e)) => {
                        eprintln!("[vela] Stream open rejected: {e}");
                        let _ = send_socks5_error(&sock, 0x04);
                        return;
                    }
                    Err(_) => {
                        let _ = send_socks5_error(&sock, 0x01);
                        return;
                    }
                };

                if send_socks5_success(&sock).is_err() {
                    let _ = cmd_tx_stream.send(SessionCmd::Reset(generation, stream_id));
                    return;
                }

                let Ok(mut read_sock) = sock.try_clone() else {
                    let _ = cmd_tx_stream.send(SessionCmd::Reset(generation, stream_id));
                    return;
                };
                let mut write_sock = sock;

                let cmd_tx_reset = cmd_tx_stream.clone();
                // SOCKS5 Writer thread: reads data received from Vela server and writes to local app
                thread::spawn(move || {
                    while let Ok(chunk) = server_data_rx.recv() {
                        if write_sock.write_all(&chunk.data).is_err() {
                            let _ = write_sock.shutdown(std::net::Shutdown::Both);
                            let _ = cmd_tx_reset.send(SessionCmd::Reset(generation, stream_id));
                            break;
                        }
                        stream_consumed.fetch_add(chunk.data.len() as u64, Ordering::Relaxed);
                    }
                    let _ = write_sock.shutdown(std::net::Shutdown::Write);
                });

                // SOCKS5 Reader thread: reads from local app socket, sends to Vela driver
                let cmd_tx_fin = cmd_tx_stream.clone();
                thread::spawn(move || {
                    let mut buf = vec![0u8; 60_000];
                    loop {
                        match read_sock.read(&mut buf) {
                            Ok(0) => {
                                let _ = cmd_tx_fin.send(SessionCmd::Fin(generation, stream_id));
                                break;
                            }
                            Ok(n) => {
                                if app_data_tx.send(buf[..n].to_vec()).is_err() {
                                    let _ = cmd_tx_fin.send(SessionCmd::Fin(generation, stream_id));
                                    break;
                                }
                            }
                            Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
                            Err(_) => {
                                let _ = cmd_tx_fin.send(SessionCmd::Reset(generation, stream_id));
                                break;
                            }
                        }
                    }
                });
            });
        }
    });

    let mut pending_client_data: HashMap<StreamId, VecDeque<Vec<u8>>> = HashMap::new();
    let mut pending_fins: HashSet<StreamId> = HashSet::new();
    let mut last_ping = Instant::now();
    let mut last_inbound = Instant::now();
    let mut ping_seq: u64 = 1;
    let mut generation: u64 = 0;
    let mut conn_consumed = Arc::new(AtomicU64::new(0));

    // Main Vela Driver Loop
    loop {
        // Heartbeat PING every 15s to keep NAT router tables alive.
        // Avoid ping flooding if a previous ping is still outstanding.
        if last_ping.elapsed() >= Duration::from_secs(15) {
            if !driver.has_pending_pings() {
                last_ping = Instant::now();
                let opaque = 0x5645_4c41_0000_0000 | (ping_seq & 0xFFFF_FFFF);
                ping_seq = ping_seq.wrapping_add(1);
                let _ = driver.send_ping(opaque);
            } else if last_ping.elapsed() >= Duration::from_secs(45)
                && last_inbound.elapsed() >= Duration::from_secs(45)
            {
                // Heartbeat timeout: peer or NAT router blackholed the TCP connection
                eprintln!(
                    "[vela] Heartbeat timed out (>45s without authenticated traffic), reconnecting..."
                );
                let _ = driver.stream_mut().shutdown(std::net::Shutdown::Both);
                generation = generation.wrapping_add(1);
                conn_consumed = Arc::new(AtomicU64::new(0));
                for entry in streams.values() {
                    let _ = entry.cancel_sock.shutdown(std::net::Shutdown::Both);
                }
                streams.clear();
                for association in udp_associations.values() {
                    let _ = association.control_sock.shutdown(std::net::Shutdown::Both);
                }
                udp_associations.clear();
                udp_routes.clear();
                pending_client_data.clear();
                pending_fins.clear();
                loop {
                    thread::sleep(Duration::from_millis(500));
                    match connect_client(
                        &connect_config,
                        &client_static,
                        &server_static,
                        session_config,
                    ) {
                        Ok(mut new_driver) => {
                            new_driver.enable_traffic_morphing(true);
                            new_driver.set_defer_flush(true);
                            driver = new_driver;
                            let _ = driver
                                .stream_mut()
                                .set_read_timeout(Some(PUMP_POLL_TIMEOUT));
                            let _ = driver.stream_mut().set_nodelay(true);
                            println!(
                                "[vela] Re-established authenticated Vela session to {server_addr}"
                            );
                            last_ping = Instant::now();
                            last_inbound = Instant::now();
                            break;
                        }
                        Err(err) => {
                            eprintln!("[vela] Reconnection attempt failed: {err}");
                        }
                    }
                }
            }
        }

        // 1. Process pending session commands (stream opens, local app data)
        while let Ok(cmd) = cmd_rx.try_recv() {
            match cmd {
                SessionCmd::Open {
                    endpoint,
                    resp_tx,
                    cancel_sock,
                    data_rx,
                } => match driver.open_stream(endpoint, PROXY_STREAM_WINDOW) {
                    Ok(id) => {
                        let (app_writer_tx, app_writer_rx) = mpsc::channel();
                        streams.insert(
                            id,
                            StreamEntry {
                                cancel_sock,
                                data_rx,
                                consumed: Arc::new(AtomicU64::new(0)),
                                input_drained: false,
                                app_writer_tx: Some(app_writer_tx),
                                local_fin: false,
                                remote_fin: false,
                                open_reply: Some((resp_tx, app_writer_rx)),
                            },
                        );
                    }
                    Err(e) => {
                        let _ = resp_tx.send(Err(format!("{e}")));
                    }
                },
                SessionCmd::Reset(command_generation, id) => {
                    if command_generation == generation && streams.contains_key(&id) {
                        if let Some(entry) = streams.remove(&id) {
                            let _ = entry.cancel_sock.shutdown(std::net::Shutdown::Both);
                        }
                        pending_client_data.remove(&id);
                        pending_fins.remove(&id);
                        driver.reset_stream(id, StreamResetCode::ApplicationError)?;
                    }
                }
                SessionCmd::Fin(command_generation, id) => {
                    if command_generation == generation && streams.contains_key(&id) {
                        pending_fins.insert(id);
                    }
                }
                SessionCmd::UdpAssociate {
                    control_sock,
                    udp_socket,
                    resp_tx,
                } => {
                    if udp_associations.len() >= 64 {
                        let _ = control_sock.shutdown(std::net::Shutdown::Both);
                        continue;
                    }
                    let Ok(peer) = control_sock.peer_addr() else {
                        continue;
                    };
                    let Ok(bound) = udp_socket.local_addr() else {
                        continue;
                    };
                    let id = next_association_id;
                    next_association_id = next_association_id.wrapping_add(1).max(1);
                    udp_associations.insert(
                        id,
                        UdpAssociation {
                            control_sock,
                            socket: udp_socket,
                            peer_ip: peer.ip(),
                            client_addr: None,
                        },
                    );
                    let _ = resp_tx.send((id, bound));
                }
                SessionCmd::UdpPacket(association_id, source, endpoint, payload) => {
                    let Some(association) = udp_associations.get_mut(&association_id) else {
                        continue;
                    };
                    if source.ip() != association.peer_ip
                        || association
                            .client_addr
                            .is_some_and(|pinned| pinned != source)
                    {
                        continue;
                    }
                    association.client_addr = Some(source);
                    let existing = udp_routes.iter().find_map(|(&context_id, route)| {
                        (route.association_id == association_id && route.endpoint == endpoint)
                            .then_some(context_id)
                    });
                    if let Some(context_id) = existing {
                        let route = udp_routes.get_mut(&context_id).expect("route exists");
                        if route.active {
                            let _ = driver.send_datagram(context_id, payload);
                        } else if route.pending.len() < 8 {
                            route.pending.push_back(payload);
                        }
                        continue;
                    }
                    if udp_routes.len() >= 128 {
                        continue;
                    }
                    match driver.open_datagram(endpoint.clone()) {
                        Ok(context_id) => {
                            let mut pending = VecDeque::new();
                            pending.push_back(payload);
                            udp_routes.insert(
                                context_id,
                                UdpRoute {
                                    association_id,
                                    endpoint,
                                    active: false,
                                    pending,
                                },
                            );
                        }
                        Err(error) if error.is_terminal() => return Err(error),
                        Err(_) => {}
                    }
                }
                SessionCmd::CloseUdp(association_id) => {
                    udp_associations.remove(&association_id);
                    let contexts: Vec<_> = udp_routes
                        .iter()
                        .filter_map(|(&context_id, route)| {
                            (route.association_id == association_id).then_some(context_id)
                        })
                        .collect();
                    for context_id in contexts {
                        udp_routes.remove(&context_id);
                        let _ = driver.close_datagram(context_id, DatagramCloseReason::Normal);
                    }
                }
            }
        }

        // 2. Forward data from local apps to Vela stream
        let stream_ids: Vec<StreamId> = streams.keys().copied().collect();
        for id in stream_ids {
            if let Some(entry) = streams.get_mut(&id) {
                let queue = pending_client_data.entry(id).or_default();
                while queue.len() < 8 {
                    match entry.data_rx.try_recv() {
                        Ok(chunk) => queue.push_back(chunk),
                        Err(mpsc::TryRecvError::Empty) => break,
                        Err(mpsc::TryRecvError::Disconnected) => {
                            entry.input_drained = true;
                            break;
                        }
                    }
                }
            }

            if let Some(queue) = pending_client_data.get_mut(&id) {
                while let Some(chunk) = queue.front() {
                    let len = chunk.len() as u64;
                    if driver.conn_send_credit() < len
                        || driver.stream_send_credit(id).unwrap_or(0) < len
                    {
                        break;
                    }
                    let Some(chunk) = queue.pop_front() else {
                        break;
                    };
                    match driver.send_stream_data(id, chunk) {
                        Ok(()) => {}
                        Err(e) if e.is_terminal() => {
                            eprintln!("[vela] Terminal error sending stream {id:?} data: {e}");
                            return Err(e);
                        }
                        Err(e) => {
                            eprintln!("[vela] Non-terminal error sending stream {id:?} data: {e}");
                        }
                    }
                }
            }
        }
        pending_client_data.retain(|_, q| !q.is_empty());

        // Send FINs for streams that have finished sending all local data
        let fins_to_send: Vec<StreamId> = pending_fins
            .iter()
            .copied()
            .filter(|id| {
                !pending_client_data.contains_key(id)
                    && streams.get(id).is_some_and(|entry| entry.input_drained)
            })
            .collect();

        for id in fins_to_send {
            pending_fins.remove(&id);
            let _ = driver.finish_stream(id);
            if let Some(entry) = streams.get_mut(&id) {
                entry.local_fin = true;
                if entry.remote_fin {
                    streams.remove(&id);
                }
            }
        }

        // Credit follows actual socket consumption, not queue insertion.
        let maximum = conn_consumed
            .load(Ordering::Relaxed)
            .saturating_add(PROXY_CONN_WINDOW);
        if maximum.saturating_sub(driver.conn_receive_window().1) >= PROXY_CONN_WINDOW / 8 {
            driver.grant_connection_credit(maximum)?;
        }
        for (&id, entry) in &streams {
            if !entry.remote_fin {
                if let Ok((_, advertised)) = driver.stream_receive_window(id) {
                    let maximum = entry
                        .consumed
                        .load(Ordering::Relaxed)
                        .saturating_add(PROXY_STREAM_WINDOW);
                    if maximum.saturating_sub(advertised) >= PROXY_STREAM_WINDOW / 8 {
                        driver.grant_stream_credit(id, maximum)?;
                    }
                }
            }
        }

        driver.flush_outbound()?;

        // 3. Pump inbound records from Vela server in batches
        let mut pumped_any = false;
        for _ in 0..16 {
            match driver.pump_inbound() {
                Ok(report) => {
                    conn_consumed.fetch_add(report.discarded_stream_bytes, Ordering::Relaxed);
                    pumped_any = true;
                    last_inbound = Instant::now();
                    for (id, status) in report.stream_open_results {
                        if let Some(entry) = streams.get_mut(&id) {
                            if let Some((reply, rx)) = entry.open_reply.take() {
                                if status == OpenResultStatus::Ok {
                                    let _ = reply.send(Ok((
                                        generation,
                                        id,
                                        rx,
                                        Arc::clone(&entry.consumed),
                                    )));
                                } else {
                                    let _ =
                                        reply.send(Err(format!("upstream rejected: {status:?}")));
                                    streams.remove(&id);
                                    pending_client_data.remove(&id);
                                    pending_fins.remove(&id);
                                }
                            }
                        }
                    }
                    for (context_id, status) in report.datagram_open_results {
                        if status == OpenResultStatus::Ok {
                            if let Some(route) = udp_routes.get_mut(&context_id) {
                                route.active = true;
                                while let Some(payload) = route.pending.pop_front() {
                                    if driver.send_datagram(context_id, payload).is_err() {
                                        break;
                                    }
                                }
                            }
                        } else {
                            udp_routes.remove(&context_id);
                        }
                    }
                    for (context_id, payload) in report.delivered_datagrams {
                        let Some(route) = udp_routes.get(&context_id) else {
                            continue;
                        };
                        let Some(association) = udp_associations.get(&route.association_id) else {
                            continue;
                        };
                        let Some(client_addr) = association.client_addr else {
                            continue;
                        };
                        if let Ok(packet) = encode_udp_datagram(&route.endpoint, &payload) {
                            let _ = association.socket.send_to(&packet, client_addr);
                        }
                    }
                    for (context_id, _reason) in report.datagram_closes {
                        udp_routes.remove(&context_id);
                    }
                    for (id, data) in report.delivered {
                        let data = ReceivedChunk {
                            data,
                            conn_consumed: Arc::clone(&conn_consumed),
                        };
                        if let Some(entry) = streams.get(&id) {
                            if let Some(ref tx) = entry.app_writer_tx {
                                let _ = tx.send(data);
                            }
                        }
                    }

                    for id in report.stream_fins {
                        if let Some(entry) = streams.get_mut(&id) {
                            entry.remote_fin = true;
                            // Drop sender to notify the local writer thread of EOF
                            entry.app_writer_tx.take();
                            if entry.local_fin {
                                streams.remove(&id);
                            }
                        }
                    }

                    for (id, _code) in report.stream_resets {
                        if let Some(entry) = streams.remove(&id) {
                            let _ = entry.cancel_sock.shutdown(std::net::Shutdown::Both);
                        }
                        pending_client_data.remove(&id);
                        pending_fins.remove(&id);
                    }
                }
                Err(TransportError::Io(ref e))
                    if e.kind() == ErrorKind::TimedOut || e.kind() == ErrorKind::WouldBlock =>
                {
                    break;
                }
                Err(e) => {
                    eprintln!("[vela] Tunnel disconnected ({e}), reconnecting...");
                    generation = generation.wrapping_add(1);
                    conn_consumed = Arc::new(AtomicU64::new(0));
                    for entry in streams.values() {
                        let _ = entry.cancel_sock.shutdown(std::net::Shutdown::Both);
                    }
                    streams.clear();
                    for association in udp_associations.values() {
                        let _ = association.control_sock.shutdown(std::net::Shutdown::Both);
                    }
                    udp_associations.clear();
                    udp_routes.clear();
                    pending_client_data.clear();
                    pending_fins.clear();
                    loop {
                        thread::sleep(Duration::from_millis(500));
                        match connect_client(
                            &connect_config,
                            &client_static,
                            &server_static,
                            session_config,
                        ) {
                            Ok(mut new_driver) => {
                                new_driver.enable_traffic_morphing(true);
                                new_driver.set_defer_flush(true);
                                driver = new_driver;
                                let _ = driver
                                    .stream_mut()
                                    .set_read_timeout(Some(PUMP_POLL_TIMEOUT));
                                let _ = driver.stream_mut().set_nodelay(true);
                                println!(
                                    "[vela] Re-established authenticated Vela session to {server_addr}"
                                );
                                last_ping = Instant::now();
                                last_inbound = Instant::now();
                                break;
                            }
                            Err(err) => {
                                eprintln!("[vela] Reconnection attempt failed: {err}");
                            }
                        }
                    }
                    break;
                }
            }
        }
        if !pumped_any {
            thread::yield_now();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Socks5Request, decode_udp_datagram, encode_udp_datagram, handle_socks5_handshake};
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::thread;
    use vela_proto::{Endpoint, Port};

    #[test]
    fn udp_datagram_codec_round_trips_ipv4_and_empty_payload() {
        let endpoint = Endpoint::ipv4([127, 0, 0, 1], Port::new(53).expect("port"));
        let encoded = encode_udp_datagram(&endpoint, &[]).expect("encode");
        let (decoded, payload) = decode_udp_datagram(&encoded).expect("decode");
        assert_eq!(decoded, endpoint);
        assert!(payload.is_empty());
    }

    #[test]
    fn udp_datagram_codec_round_trips_domain_and_ipv6() {
        let endpoints = [
            Endpoint::Domain {
                name: vela_proto::DomainName::new("example.com".to_string()).expect("domain"),
                port: Port::new(443).expect("port"),
            },
            Endpoint::Ipv6 {
                address: "2001:db8::1"
                    .parse::<std::net::Ipv6Addr>()
                    .expect("IPv6")
                    .octets(),
                port: Port::new(443).expect("port"),
            },
        ];
        for endpoint in endpoints {
            let encoded = encode_udp_datagram(&endpoint, &[1, 2, 3]).expect("encode");
            let (decoded, payload) = decode_udp_datagram(&encoded).expect("decode");
            assert_eq!(decoded, endpoint);
            assert_eq!(payload, [1, 2, 3]);
        }
    }

    #[test]
    fn udp_datagram_codec_rejects_fragments_and_truncated_inputs() {
        let fragmented = [0, 0, 1, 1, 127, 0, 0, 1, 0, 53, 1];
        let truncated = [0, 0, 0, 1, 127, 0, 0, 1, 0];
        assert!(decode_udp_datagram(&fragmented).is_err());
        assert!(decode_udp_datagram(&truncated).is_err());
    }

    #[test]
    fn socks5_handshake_accepts_udp_associate_with_wildcard_address() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener");
        let mut client =
            TcpStream::connect(listener.local_addr().expect("address")).expect("client");
        let (server, _) = listener.accept().expect("accept");
        let worker = thread::spawn(move || handle_socks5_handshake(&server));

        client
            .write_all(&[5, 1, 0, 5, 3, 0, 1, 0, 0, 0, 0, 0, 0])
            .expect("request");
        let mut method = [0; 2];
        client.read_exact(&mut method).expect("method response");
        assert_eq!(method, [5, 0]);
        assert_eq!(
            worker.join().expect("worker"),
            Ok(Socks5Request::UdpAssociate)
        );
    }
}
