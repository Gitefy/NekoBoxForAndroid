#![forbid(unsafe_code)]
//! Vela client reference binary.
//!
//! This is not a production release (frozen spec §21). Protocol or crypto
//! failure has no direct fallback.

use std::fmt::Write as _;
use std::net::{SocketAddr, ToSocketAddrs};
use std::path::Path;
use vela_client::{ClientStaticPrivateKey, ServerStaticPublicKey, run_socks5_proxy};

fn decode_hex_32(s: &str) -> Result<[u8; 32], String> {
    let s = s.trim();
    if s.len() != 64 {
        return Err(format!("expected 64 hex characters, got {}", s.len()));
    }
    let mut out = [0u8; 32];
    for i in 0..32 {
        out[i] = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16)
            .map_err(|e| format!("invalid hex char at byte {i}: {e}"))?;
    }
    Ok(out)
}

fn load_hex_key(arg: &str) -> Result<[u8; 32], String> {
    if Path::new(arg).exists() {
        let content =
            std::fs::read_to_string(arg).map_err(|e| format!("read file '{arg}': {e}"))?;
        decode_hex_32(&content)
    } else {
        decode_hex_32(arg)
    }
}

fn resolve_server_addr(value: &str) -> Result<SocketAddr, String> {
    value
        .to_socket_addrs()
        .map_err(|error| format!("failed to resolve server address: {error}"))?
        .next()
        .ok_or_else(|| "server address did not resolve to any IP address".to_string())
}

fn print_usage() {
    eprintln!("vela reference implementation; not a production release");
    eprintln!("no direct fallback on protocol or crypto failure");
    eprintln!();
    eprintln!("Usage:");
    eprintln!("  vela smoke");
    eprintln!("  vela keygen");
    eprintln!(
        "  vela run --listen <SOCKS_ADDR:PORT> --server <SERVER_ADDR:PORT> --key <HEX|PATH> --server-key <HEX|PATH>"
    );
}

// Keep the small reference CLI dispatcher in one place.
#[allow(clippy::too_many_lines)]
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        print_usage();
        std::process::exit(2);
    }

    match args[1].as_str() {
        "smoke" => {
            let pool = vela_client::smoke_runtime();
            assert!(pool.is_empty());
            println!("vela smoke: client pool constructed; no direct fallback");
        }
        "keygen" => {
            let (privkey, pubkey) = ClientStaticPrivateKey::generate_keypair();
            let priv_hex: String =
                privkey
                    .secret_bytes()
                    .iter()
                    .fold(String::with_capacity(64), |mut output, b| {
                        let _ = write!(&mut output, "{b:02x}");
                        output
                    });
            let pub_hex: String =
                pubkey
                    .as_bytes()
                    .iter()
                    .fold(String::with_capacity(64), |mut output, b| {
                        let _ = write!(&mut output, "{b:02x}");
                        output
                    });
            println!("# Vela Client Static Keys");
            println!("CLIENT_PRIVATE_KEY={priv_hex}");
            println!("CLIENT_PUBLIC_KEY={pub_hex}");
        }
        "run" => {
            let mut listen_addr: Option<SocketAddr> = None;
            let mut server_addr: Option<String> = None;
            let mut client_key_bytes: Option<[u8; 32]> = None;
            let mut server_pub_bytes: Option<[u8; 32]> = None;

            let mut i = 2;
            while i < args.len() {
                match args[i].as_str() {
                    "--listen" => {
                        i += 1;
                        if i >= args.len() {
                            eprintln!("Missing value for --listen");
                            std::process::exit(2);
                        }
                        listen_addr = Some(args[i].parse().unwrap_or_else(|e| {
                            eprintln!("Invalid listen address '{}': {e}", args[i]);
                            std::process::exit(2);
                        }));
                    }
                    "--server" => {
                        i += 1;
                        if i >= args.len() {
                            eprintln!("Missing value for --server");
                            std::process::exit(2);
                        }
                        server_addr = Some(args[i].clone());
                    }
                    "--key" => {
                        i += 1;
                        if i >= args.len() {
                            eprintln!("Missing value for --key");
                            std::process::exit(2);
                        }
                        client_key_bytes = Some(load_hex_key(&args[i]).unwrap_or_else(|e| {
                            eprintln!("Failed to load client private key: {e}");
                            std::process::exit(2);
                        }));
                    }
                    "--server-key" => {
                        i += 1;
                        if i >= args.len() {
                            eprintln!("Missing value for --server-key");
                            std::process::exit(2);
                        }
                        server_pub_bytes = Some(load_hex_key(&args[i]).unwrap_or_else(|e| {
                            eprintln!("Failed to load server public key: {e}");
                            std::process::exit(2);
                        }));
                    }
                    other => {
                        eprintln!("Unknown argument: {other}");
                        print_usage();
                        std::process::exit(2);
                    }
                }
                i += 1;
            }

            let Some(listen) = listen_addr else {
                eprintln!("Missing required --listen <ADDR:PORT>");
                std::process::exit(2);
            };

            let Some(server_value) = server_addr else {
                eprintln!("Missing required --server <ADDR:PORT>");
                std::process::exit(2);
            };
            let server = resolve_server_addr(&server_value).unwrap_or_else(|error| {
                eprintln!("Invalid server address: {error}");
                std::process::exit(2);
            });

            let client_key = if let Some(bytes) = client_key_bytes {
                ClientStaticPrivateKey::new(bytes)
            } else {
                eprintln!("Missing required --key <HEX|PATH>");
                std::process::exit(2);
            };

            let server_key = if let Some(bytes) = server_pub_bytes {
                ServerStaticPublicKey::new(bytes)
            } else {
                eprintln!("Missing required --server-key <HEX|PATH>");
                std::process::exit(2);
            };

            println!(
                "vela: starting SOCKS5 proxy on {listen} -> server {server} (not a production release, no direct fallback)"
            );
            if let Err(e) = run_socks5_proxy(listen, server, client_key, server_key) {
                eprintln!("vela client error: {e}");
                std::process::exit(1);
            }
        }
        _ => {
            print_usage();
            std::process::exit(2);
        }
    }
}
