package libcore

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

func TestUDPOnlyURLTestSelection(t *testing.T) {
	cachePath, _ := json.Marshal(filepath.Join(t.TempDir(), "cache.db"))
	config := fmt.Sprintf(`{"log":{"disabled":true},"experimental":{"cache_file":{"enabled":true,"path":%s}},"outbounds":[
		{"type":"direct","tag":"direct"},
		{"type":"shadowsocks","tag":"udp-node","server":"127.0.0.1","server_port":1,"method":"aes-128-gcm","password":"test-password","network":"udp"},
		{"type":"urltest","tag":"udp-group","outbounds":["udp-node"]}
	],"route":{"final":"direct"}}`, cachePath)
	b, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, ok := b.Outbound().Outbound("udp-group")
	if !ok {
		t.Fatal("missing group")
	}
	if selected := queryGroupSelection(outbound); selected != "udp-node" {
		t.Fatalf("UDP winner lost: %q", selected)
	}
}

func TestCurrentGroupSelectionsParsing(t *testing.T) {
	// Test newline/tab formatted string parsing
	raw := "group-1\tnode-a\ngroup-2\tnode-b\n"
	lines := strings.Split(strings.TrimSpace(raw), "\n")
	if len(lines) != 2 {
		t.Fatalf("expected 2 lines, got %d", len(lines))
	}
	parts1 := strings.Split(lines[0], "\t")
	if len(parts1) != 2 || parts1[0] != "group-1" || parts1[1] != "node-a" {
		t.Errorf("unexpected line 1: %v", parts1)
	}
	parts2 := strings.Split(lines[1], "\t")
	if len(parts2) != 2 || parts2[0] != "group-2" || parts2[1] != "node-b" {
		t.Errorf("unexpected line 2: %v", parts2)
	}
}

func TestCurrentGroupSelectionsNilBox(t *testing.T) {
	var b *BoxInstance
	res := b.CurrentGroupSelections("group-1\ngroup-2")
	if res == nil || res.Value != "" {
		t.Errorf("expected empty string on nil box, got %v", res)
	}
}
