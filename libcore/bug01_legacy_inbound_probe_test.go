package libcore

import "testing"

// TestBug01LegacyInboundFieldsRejected pins the BUG01 failure mechanism: the
// pinned sing-box core rejects legacy inbound fields on listen inbounds, so
// ConfigBuilder must never emit them. If a future core upgrade accepts them
// again, this test failing is the signal to re-check ConfigBuilder.
func TestBug01LegacyInboundFieldsRejected(t *testing.T) {
	cases := []struct {
		name  string
		field string
	}{
		{name: "sniff", field: `"sniff": true`},
		{name: "sniff_override_destination", field: `"sniff_override_destination": true`},
		{name: "domain_strategy", field: `"domain_strategy": "prefer_ipv4"`},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := `{
  "log": {"level": "info"},
  "inbounds": [{
    "type": "mixed",
    "tag": "mixed-in",
    "listen": "127.0.0.1",
    "listen_port": 1080,
    ` + tc.field + `
  }],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {"final": "direct"}
}`

			b, err := NewSingBoxInstance(cfg, nil)
			if err == nil {
				b.Close()
				t.Fatalf("legacy mixed inbound field should be rejected by the 1.15 core")
			}
		})
	}
}

// TestBug01MigratedInboundShapeAccepted mirrors the inbound/route shape
// ConfigBuilder emits after the sing-box 1.15 migration: inbounds without
// legacy fields, and sniff/domain-strategy semantics carried by route rule
// actions (sniff, resolve) ahead of the DNS hijack rules.
func TestBug01MigratedInboundShapeAccepted(t *testing.T) {
	cfg := `{
  "log": {"level": "info"},
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "stack": "go",
      "address": ["172.19.0.1/30"],
      "interface_name": "tun0",
      "mtu": 9000,
      "auto_route": true
    },
    {
      "type": "mixed",
      "tag": "mixed-in",
      "listen": "127.0.0.1",
      "listen_port": 2080
    }
  ],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {
    "final": "direct",
    "auto_detect_interface": true,
    "rules": [
      {"port": [53], "action": "hijack-dns"},
      {"action": "sniff"},
      {"action": "resolve", "strategy": "prefer_ipv4"},
      {"protocol": ["dns"], "action": "hijack-dns"}
    ]
  },
  "dns": {
    "servers": [
      {"tag": "dns-local", "type": "local"},
      {"tag": "dns-direct", "type": "udp", "server": "1.1.1.1", "server_port": 53, "domain_resolver": "dns-local"}
    ],
    "rules": [
      {"outbound": ["any"], "server": "dns-direct"}
    ],
    "final": "dns-direct",
    "strategy": "prefer_ipv4"
  }
}`

	b, err := NewSingBoxInstance(cfg, nil)
	if err != nil {
		t.Fatalf("migrated inbound/route shape should parse on sing-box 1.15: %v", err)
	}
	b.Close()
}
