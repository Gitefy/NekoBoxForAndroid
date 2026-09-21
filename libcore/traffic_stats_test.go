package libcore

import (
	"encoding/binary"
	"testing"

	"github.com/sagernet/sing-box/experimental/v2rayapi"
)

func TestParseOutboundStatName(t *testing.T) {
	cases := []struct {
		input       string
		expectedTag string
		expectedDir string
		expectedOk  bool
	}{
		{"outbound>>>proxy>>>traffic>>>uplink", "proxy", "uplink", true},
		{"outbound>>>bypass>>>traffic>>>downlink", "bypass", "downlink", true},
		{"outbound>>>node>>>special>>>traffic>>>uplink", "node>>>special", "uplink", true},
		{"inbound>>>http>>>traffic>>>uplink", "", "", false},
		{"outbound>>>proxy>>>connections", "", "", false},
		{"outbound>>>proxy>>>traffic>>>unknown", "", "", false},
	}

	for _, tc := range cases {
		tag, dir, ok := parseOutboundStatName(tc.input)
		if ok != tc.expectedOk || tag != tc.expectedTag || dir != tc.expectedDir {
			t.Errorf("parseOutboundStatName(%q) = (%q, %q, %v); want (%q, %q, %v)",
				tc.input, tag, dir, ok, tc.expectedTag, tc.expectedDir, tc.expectedOk)
		}
	}
}

func TestTrafficStatsEncodingAndEquivalence(t *testing.T) {
	reg := newTrafficStatsRegistry("proxy\nbypass\nnode-1\nnode-2")

	stats := []*v2rayapi.Stat{
		{Name: "outbound>>>proxy>>>traffic>>>uplink", Value: 200},
		{Name: "outbound>>>proxy>>>traffic>>>downlink", Value: 100},
		{Name: "outbound>>>bypass>>>traffic>>>downlink", Value: 50},
		{Name: "outbound>>>node-1>>>traffic>>>uplink", Value: 0},
		{Name: "outbound>>>unindexed-group>>>traffic>>>uplink", Value: 400},
		{Name: "outbound>>>unindexed-group>>>traffic>>>downlink", Value: 300},
	}

	buf := encodeTrafficStatsSnapshot(stats, reg)
	if len(buf) < 3 {
		t.Fatalf("snapshot buffer too short: %d", len(buf))
	}

	if buf[0] != trafficStatsSnapshotVersion {
		t.Fatalf("unexpected version: %d", buf[0])
	}

	indexedCount := int(binary.BigEndian.Uint16(buf[1:3]))
	// proxy (uplink=200, downlink=100) and bypass (downlink=50) are indexed; node-1 is zero delta.
	if indexedCount != 2 {
		t.Fatalf("expected 2 indexed entries, got %d", indexedCount)
	}

	offset := 3
	type decodedIndexed struct {
		idx uint16
		rx  int64
		tx  int64
	}
	var indexed []decodedIndexed
	for i := 0; i < indexedCount; i++ {
		idx := binary.BigEndian.Uint16(buf[offset : offset+2])
		rx := int64(binary.BigEndian.Uint64(buf[offset+2 : offset+10]))
		tx := int64(binary.BigEndian.Uint64(buf[offset+10 : offset+18]))
		offset += 18
		indexed = append(indexed, decodedIndexed{idx, rx, tx})
	}

	proxyIdx, _ := reg.lookup("proxy")
	bypassIdx, _ := reg.lookup("bypass")

	if indexed[0].idx != proxyIdx || indexed[0].rx != 100 || indexed[0].tx != 200 {
		t.Errorf("unexpected proxy indexed entry: %+v", indexed[0])
	}
	if indexed[1].idx != bypassIdx || indexed[1].rx != 50 || indexed[1].tx != 0 {
		t.Errorf("unexpected bypass indexed entry: %+v", indexed[1])
	}

	namedCount := int(binary.BigEndian.Uint16(buf[offset : offset+2]))
	offset += 2
	if namedCount != 1 {
		t.Fatalf("expected 1 named entry, got %d", namedCount)
	}

	tagLen := int(binary.BigEndian.Uint16(buf[offset : offset+2]))
	offset += 2
	tagName := string(buf[offset : offset+tagLen])
	offset += tagLen
	namedRx := int64(binary.BigEndian.Uint64(buf[offset : offset+8]))
	namedTx := int64(binary.BigEndian.Uint64(buf[offset+8 : offset+16]))

	if tagName != "unindexed-group" || namedRx != 300 || namedTx != 400 {
		t.Errorf("unexpected named entry: tag=%q rx=%d tx=%d", tagName, namedRx, namedTx)
	}
}

func TestTrafficStatsEncodingEmptyAndZero(t *testing.T) {
	reg := newTrafficStatsRegistry("proxy\nbypass")
	buf := encodeTrafficStatsSnapshot(nil, reg)
	if len(buf) != 5 {
		t.Fatalf("expected 5 bytes for empty snapshot, got %d", len(buf))
	}
	if buf[0] != 1 || binary.BigEndian.Uint16(buf[1:3]) != 0 || binary.BigEndian.Uint16(buf[3:5]) != 0 {
		t.Errorf("malformed empty snapshot: %v", buf)
	}

	zeroStats := []*v2rayapi.Stat{
		{Name: "outbound>>>proxy>>>traffic>>>uplink", Value: 0},
		{Name: "outbound>>>proxy>>>traffic>>>downlink", Value: 0},
	}
	buf2 := encodeTrafficStatsSnapshot(zeroStats, reg)
	if len(buf2) != 5 {
		t.Fatalf("expected 5 bytes for zero-delta snapshot, got %d", len(buf2))
	}
}
