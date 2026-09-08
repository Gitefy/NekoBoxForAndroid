package libcore

import (
	"encoding/json"
	"fmt"
	"runtime"
	"testing"
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
	if runtime.GOOS != "android" {
		t.Skip("WireGuard endpoint creation requires gVisor; run on Android or with -tags with_gvisor")
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
