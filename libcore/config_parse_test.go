package libcore

import (
	"encoding/json"
	"fmt"
	"testing"

	"github.com/sagernet/sing-tun"
)

// makeTUNConfig returns a minimal sing-box config with the given tun stack and
// address list. Mirrors the JSON produced by ConfigBuilder on the Android side.
func makeTUNConfig(stack string, addresses []string) string {
	addrs, _ := json.Marshal(addresses)
	cfg := fmt.Sprintf(`{
  "log": {"level": "info"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "block", "tag": "block"}
  ],
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "stack": %q,
      "address": %s,
      "interface_name": "tun0",
      "mtu": 9000,
      "auto_route": true,
      "strict_route": false,
      "endpoint_independent_nat": false
    }
  ],
  "route": {
    "auto_detect_interface": true,
    "final": "direct"
  }
}`, stack, string(addrs))
	return cfg
}

func TestTUNSchemaVariants(t *testing.T) {
	stacks := []string{"go", "gvisor", "system", "mixed"}
	cases := []struct {
		name    string
		address []string
	}{
		{"ipv4", []string{"172.19.0.1/30"}},
		{"ipv6", []string{"fdfe:dcba:9876::1/126"}},
		{"dual", []string{"172.19.0.1/30", "fdfe:dcba:9876::1/126"}},
	}

	for _, stack := range stacks {
		for _, c := range cases {
			t.Run(fmt.Sprintf("%s/%s", stack, c.name), func(t *testing.T) {
				cfg := makeTUNConfig(stack, c.address)
				b, err := NewSingBoxInstance(cfg, nil)
				if err != nil {
					t.Fatalf("NewSingBoxInstance failed: %v", err)
				}
				defer b.Close()

				// Verify the tun inbound JSON shape is what the Android side emits.
				var raw map[string]any
				if err := json.Unmarshal([]byte(cfg), &raw); err != nil {
					t.Fatalf("unmarshal failed: %v", err)
				}
				inbounds := raw["inbounds"].([]any)
				tunRaw := inbounds[0].(map[string]any)
				if got := tunRaw["stack"].(string); got != stack {
					t.Errorf("stack = %q, want %q", got, stack)
				}
				addrList := tunRaw["address"].([]any)
				if len(addrList) != len(c.address) {
					t.Errorf("address length = %d, want %d", len(addrList), len(c.address))
				}
			})
		}
	}
}

// protocolSmokeConfig mirrors a NekoBox-generated config containing all
// supported protocol outbounds. It is submitted to the real sing-box 1.15 core
// to catch schema-breaking changes, not just JSON syntax errors.
func protocolSmokeConfig() string {
	return `{
  "log": {"level": "info"},

  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "stack": "go",
      "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
      "interface_name": "tun0",
      "mtu": 9000,
      "auto_route": true,
    }
  ],
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "block", "tag": "block"},
    {
      "type": "vless",
      "tag": "node-vless",
      "server": "1.2.3.4",
      "server_port": 443,
      "uuid": "c4ad1951-087c-4a4b-99f0-9c1e0c99f3d1",
      "tls": {"enabled": true, "server_name": "example.com"}
    },
    {
      "type": "vmess",
      "tag": "node-vmess",
      "server": "1.2.3.4",
      "server_port": 443,
      "uuid": "c4ad1951-087c-4a4b-99f0-9c1e0c99f3d2",
      "security": "auto"
    },
    {
      "type": "trojan",
      "tag": "node-trojan",
      "server": "1.2.3.4",
      "server_port": 443,
      "password": "test-password",
      "tls": {"enabled": true, "server_name": "example.com"}
    },
    {
      "type": "shadowsocks",
      "tag": "node-ss",
      "server": "1.2.3.4",
      "server_port": 8388,
      "method": "aes-256-gcm",
      "password": "test-password"
    },
    {
      "type": "hysteria2",
      "tag": "node-hy2",
      "server": "1.2.3.4",
      "server_port": 443,
      "password": "test-password",
      "tls": {"enabled": true, "server_name": "example.com"}
    },
    {
      "type": "tuic",
      "tag": "node-tuic",
      "server": "1.2.3.4",
      "server_port": 443,
      "uuid": "c4ad1951-087c-4a4b-99f0-9c1e0c99f3d3",
      "password": "test-password",
      "tls": {"enabled": true, "server_name": "example.com"},
      "congestion_control": "bbr"
    },
    {
      "type": "anytls",
      "tag": "node-anytls",
      "server": "1.2.3.4",
      "server_port": 443,
      "password": "test-password",
      "tls": {"enabled": true, "server_name": "example.com"}
    },
    {
      "type": "snell",
      "tag": "node-snell4",
      "server": "1.2.3.4",
      "server_port": 443,
      "version": 4,
      "psk": "test-psk",
      "obfs_mode": "tls",
      "obfs_host": "example.com"
    },
    {
      "type": "snell",
      "tag": "node-snell6",
      "server": "1.2.3.4",
      "server_port": 443,
      "version": 6,
      "psk": "test-psk",
      "mode": "default"
    },
    {
      "type": "ssh",
      "tag": "node-ssh",
      "server": "1.2.3.4",
      "server_port": 22,
      "user": "root",
      "password": "test-password"
    },
    {
      "type": "shadowtls",
      "tag": "node-shadowtls",
      "server": "1.2.3.4",
      "server_port": 443,
      "password": "test-password",
      "version": 3,
      "tls": {"enabled": true, "server_name": "example.com"}
    },
    {
      "type": "socks",
      "tag": "node-socks",
      "server": "1.2.3.4",
      "server_port": 1080
    },
    {
      "type": "http",
      "tag": "node-http",
      "server": "1.2.3.4",
      "server_port": 8080,
      "username": "user",
      "password": "pass"
    },
    {
      "type": "selector",
      "tag": "proxy",
      "outbounds": ["node-vless", "direct"]
    },
    {
      "type": "urltest",
      "tag": "auto",
      "outbounds": ["node-vless", "direct"],
      "url": "http://cp.cloudflare.com/generate_204",
      "interval": "1m"
    }
  ],

  "route": {
    "auto_detect_interface": true,
    "final": "direct"
  },
  "experimental": {
    "cache_file": {"enabled": true}
  }
}`
}

func TestProtocolSmokeConfig(t *testing.T) {
	cfg := protocolSmokeConfig()
	b, err := NewSingBoxInstance(cfg, nil)
	if err != nil {
		t.Fatalf("NewSingBoxInstance failed: %v", err)
	}
	defer b.Close()

	// Quick sanity check that the selector outbound is present.
	if _, ok := b.Outbound().Outbound("proxy"); !ok {
		t.Error("selector outbound 'proxy' not found")
	}
}

func TestWireGuardEndpointSchema(t *testing.T) {
	if !tun.WithGVisor {
		t.Skip("WireGuard endpoint creation requires -tags with_gvisor,with_wireguard")
	}
	cfg := `{
  "log": {"level": "info"},
  "outbounds": [
    {"type": "direct", "tag": "direct"}
  ],
  "endpoints": [
    {
      "type": "wireguard",
      "tag": "wg",
      "address": ["172.16.0.2/32", "fd00::2/128"],
      "private_key": "GEPJmUxLGPPcnKtXBIeEVaKzWEs2RJUsZ5xzrEsQPVk=",
      "mtu": 1420,
      "peers": [
        {
          "address": "1.2.3.4",
          "port": 51820,
          "public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
          "allowed_ips": ["0.0.0.0/0", "::/0"],
          "reserved": "AQID"
        }
      ]
    }
  ],
  "route": {"final": "direct"}
}`
	b, err := NewSingBoxInstance(cfg, nil)
	if err != nil {
		t.Fatalf("NewSingBoxInstance failed: %v", err)
	}
	defer b.Close()

	// Ensure the endpoint is reachable as an outbound by tag (this is how the
	// Android app routes traffic to WireGuard after the 1.15 endpoint migration).
	if _, ok := b.Outbound().Outbound("wg"); !ok {
		t.Error("wireguard endpoint 'wg' is not reachable as outbound")
	}
	dialer, err := urlTestDetourWithTarget(b, "wg")
	if err != nil {
		t.Fatal(err)
	}
	endpoint, _ := b.Outbound().Outbound("wg")
	if dialer != endpoint {
		t.Fatal("explicit WireGuard URL test target resolved to an auxiliary outbound")
	}
}

// snellConfig returns a minimal config with a single Snell outbound of the
// given version. Used to verify which Snell versions the 1.15 core accepts.
func snellConfig(version int) string {
	return fmt.Sprintf(`{
  "log": {"level": "info"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {
      "type": "snell",
      "tag": "node-snell",
      "server": "1.2.3.4",
      "server_port": 443,
      "version": %d,
      "psk": "test-psk"
    }
  ],
  "route": {"final": "direct"}
}`, version)
}

// TestSnellSupportedVersions asserts the sing-box 1.15 core accepts Snell v4 and v6.
func TestSnellSupportedVersions(t *testing.T) {
	for _, v := range []int{4, 6} {
		t.Run(fmt.Sprintf("v%d", v), func(t *testing.T) {
			b, err := NewSingBoxInstance(snellConfig(v), nil)
			if err != nil {
				t.Fatalf("Snell v%d should be supported, NewSingBoxInstance failed: %v", v, err)
			}
			defer b.Close()
		})
	}
}

// TestSnellUnsupportedVersions asserts the core rejects legacy/incompatible
// Snell versions (v1/v2/v3/v5). The app additionally blocks these before the
// config build (see SnellBuildConfig.kt), surfacing a clear user-readable error.
func TestSnellUnsupportedVersions(t *testing.T) {
	for _, v := range []int{1, 2, 3, 5} {
		t.Run(fmt.Sprintf("v%d", v), func(t *testing.T) {
			_, err := NewSingBoxInstance(snellConfig(v), nil)
			if err == nil {
				t.Fatalf("Snell v%d should be rejected by the 1.15 core", v)
			}
		})
	}
}

// TestSSRAssertUnsupported proves ShadowsocksR is not a recognized outbound in
// the 1.15 core, which is why the app must surface a clear "SSR is not
// supported by the current sing-box 1.15 core." error instead of emitting it.
func TestSSRAssertUnsupported(t *testing.T) {
	cfg := `{
  "log": {"level": "info"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {
      "type": "shadowsocksr",
      "tag": "node-ssr",
      "server": "1.2.3.4",
      "server_port": 8388,
      "method": "aes-256-cfb",
      "password": "test-password",
      "protocol": "origin",
      "obfs": "plain"
    }
  ],
  "route": {"final": "direct"}
}`
	_, err := NewSingBoxInstance(cfg, nil)
	if err == nil {
		t.Fatal("shadowsocksr outbound should be rejected by the 1.15 core")
	}
}

// TestDNSBlockAndStrategySchema mirrors the DNS JSON ConfigBuilder emits after
// the sing-box 1.15 migration: typed servers with "domain_resolver", DNS rules
// carrying "action"/"rcode" (predefined block) or "query_type" (fakeip), and
// the query strategy as the top-level dns.strategy client option. The per-rule
// "strategy" action option must NOT be used: combined with "query_type" rules
// it is rejected at startup (see TestDNSStrategyActionWithQueryTypeRejected).
func TestDNSBlockAndStrategySchema(t *testing.T) {
	cfg := `{
  "log": {"level": "info"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "direct", "tag": "bypass"},
    {"type": "block", "tag": "block"}
  ],
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "stack": "go",
      "address": ["172.19.0.1/30"],
      "interface_name": "tun0",
      "mtu": 9000,
      "auto_route": true
    }
  ],
  "dns": {
    "servers": [
      {"tag": "dns-local", "type": "local"},
      {"tag": "dns-direct", "type": "udp", "server": "1.1.1.1", "server_port": 53, "domain_resolver": "dns-local"},
      {"tag": "dns-remote", "type": "udp", "server": "8.8.8.8", "server_port": 53, "domain_resolver": "dns-direct"},
      {"tag": "dns-fake", "type": "fakeip", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"}
    ],
    "rules": [
      {"domain_suffix": ["blocked.example"], "action": "predefined", "rcode": "NOERROR"},
      {"inbound": ["tun-in"], "server": "dns-fake", "disable_cache": true, "query_type": ["A", "AAAA"]},
      {"domain_suffix": ["example.com"], "server": "dns-remote"}
    ],
    "final": "dns-remote",
    "strategy": "prefer_ipv4"
  },
  "route": {"final": "direct", "auto_detect_interface": true}
}`
	b, err := NewSingBoxInstance(cfg, nil)
	if err != nil {
		t.Fatalf("DNS config should parse on sing-box 1.15: %v", err)
	}
	defer b.Close()
}

// TestDNSStrategyActionWithQueryTypeRejected pins the startup failure seen on
// device: a "query_type" DNS rule (fakeip) is incompatible with the legacy
// per-rule "strategy" action option and the combination is rejected when the
// DNS router initializes.
func TestDNSStrategyActionWithQueryTypeRejected(t *testing.T) {
	cfg := `{
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "dns": {
    "servers": [
      {"tag": "dns-remote", "type": "udp", "server": "8.8.8.8", "server_port": 53},
      {"tag": "dns-fake", "type": "fakeip", "inet4_range": "198.18.0.0/15"}
    ],
    "rules": [
      {"server": "dns-fake", "query_type": ["A", "AAAA"]},
      {"domain_suffix": ["example.com"], "server": "dns-remote", "strategy": "prefer_ipv4"}
    ],
    "final": "dns-remote"
  },
  "route": {"final": "direct"}
}`
	_, err := NewSingBoxInstance(cfg, nil)
	if err == nil {
		t.Fatal("query_type rule combined with rule-level strategy should be rejected at startup")
	}
}

// TestDNSLegacySchemaRejected proves the pre-migration DNS fields that would
// have caused physical-device startup failures are rejected by the 1.15 core.
func TestDNSLegacySchemaRejected(t *testing.T) {
	cases := map[string]string{
		"legacy rcode server value": `{
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "dns": {
    "servers": [{"tag": "dns-local", "type": "local"}],
    "rules": [{"domain_suffix": ["blocked.example"], "action": "predefined", "rcode": "success"}]
  },
  "route": {"final": "direct"}
}`,
		"server-level strategy": `{
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "dns": {
    "servers": [{"tag": "dns-direct", "type": "udp", "server": "1.1.1.1", "strategy": "prefer_ipv4"}]
  },
  "route": {"final": "direct"}
}`,
		"server-level address_resolver": `{
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "dns": {
    "servers": [{"tag": "dns-direct", "type": "udp", "server": "1.1.1.1", "address_resolver": "dns-local"}]
  },
  "route": {"final": "direct"}
}`,
	}
	for name, cfg := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := NewSingBoxInstance(cfg, nil)
			if err == nil {
				t.Fatalf("legacy DNS schema should be rejected by the 1.15 core")
			}
		})
	}
}

// TestRouteConcurrentDialRejected proves the NekoBox "concurrent_dial" route
// extension no longer exists in sing-box 1.15, so ConfigBuilder must not emit
// it (otherwise the whole config fails strict parsing).
func TestRouteConcurrentDialRejected(t *testing.T) {
	cfg := `{
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {"final": "direct", "concurrent_dial": false}
}`
	_, err := NewSingBoxInstance(cfg, nil)
	if err == nil {
		t.Fatal("route.concurrent_dial should be rejected by the 1.15 core")
	}
}

// TestDirectFragmentExtensionRejected proves the NekoBox fork-only "fragment"
// extension on the direct outbound no longer exists in sing-box 1.15 (TLS
// fragmentation moved to the route "tls_fragment" action / TLS options).
func TestDirectFragmentExtensionRejected(t *testing.T) {
	cfg := `{
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "direct", "tag": "fragment", "fragment": {"length": "1-5", "interval": "30-60"}}
  ],
  "route": {"final": "direct"}
}`
	_, err := NewSingBoxInstance(cfg, nil)
	if err == nil {
		t.Fatal("direct outbound 'fragment' extension should be rejected by the 1.15 core")
	}
}
