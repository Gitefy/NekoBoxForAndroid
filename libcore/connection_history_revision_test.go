package libcore

import (
	"sync/atomic"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	M "github.com/sagernet/sing/common/metadata"
)

type revisionFakeSource struct {
	conns []*trafficcontrol.TrackerMetadata
}

func (s *revisionFakeSource) Connections() []*trafficcontrol.TrackerMetadata {
	return s.conns
}

func (s *revisionFakeSource) ClosedConnections() []*trafficcontrol.TrackerMetadata {
	return nil
}

func TestRevisionEmptyToEmptyUnchanged(t *testing.T) {
	now := time.UnixMilli(1_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	rev0 := h.Revision()
	rev1 := h.MergeLive(nil)
	if rev1 != rev0 {
		t.Fatalf("expected unchanged revision on empty history, got rev0=%d, rev1=%d", rev0, rev1)
	}

	src := &revisionFakeSource{conns: nil}
	rev2 := h.MergeLive(src)
	if rev2 != rev0 {
		t.Fatalf("expected unchanged revision on nil connections, got rev0=%d, rev2=%d", rev0, rev2)
	}
}

func TestRevisionFirstFlowAdvances(t *testing.T) {
	now := time.UnixMilli(1_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	rev0 := h.Revision()
	h.Upsert(connectionFlow{
		ID:        "flow-1",
		CreatedAt: now.UnixMilli(),
		Domain:    "example.com",
	})
	rev1 := h.Revision()
	if rev1 <= rev0 {
		t.Fatalf("first flow must advance revision: rev0=%d, rev1=%d", rev0, rev1)
	}
}

func TestRevisionIdenticalFlowUnchanged(t *testing.T) {
	now := time.UnixMilli(1_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	flow := connectionFlow{
		ID:                 "flow-1",
		CreatedAt:          now.UnixMilli(),
		Domain:             "example.com",
		DestinationAddress: "1.1.1.1",
		DestinationPort:    443,
		LogicalOutbound:    "proxy",
		FinalOutboundTag:   "node-a",
		UploadBytes:        100,
		DownloadBytes:      200,
	}
	h.Upsert(flow)
	revAfterFirst := h.Revision()

	// Apply identical flow
	h.Upsert(flow)
	revAfterSecond := h.Revision()
	if revAfterSecond != revAfterFirst {
		t.Fatalf("identical flow must not advance revision: before=%d, after=%d", revAfterFirst, revAfterSecond)
	}
}

func TestRevisionUploadAndDownloadBytesAdvance(t *testing.T) {
	now := time.UnixMilli(1_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	flow := connectionFlow{
		ID:            "flow-1",
		CreatedAt:     now.UnixMilli(),
		UploadBytes:   1000,
		DownloadBytes: 2000,
	}
	h.Upsert(flow)
	rev0 := h.Revision()

	// Upload bytes advance
	flow.UploadBytes = 1500
	h.Upsert(flow)
	rev1 := h.Revision()
	if rev1 <= rev0 {
		t.Fatalf("upload byte change must advance revision: rev0=%d, rev1=%d", rev0, rev1)
	}

	// Download bytes advance
	flow.DownloadBytes = 2500
	h.Upsert(flow)
	rev2 := h.Revision()
	if rev2 <= rev1 {
		t.Fatalf("download byte change must advance revision: rev1=%d, rev2=%d", rev1, rev2)
	}

	// Lower byte count in incoming flow should not decrease or change dst
	flowLower := flow
	flowLower.UploadBytes = 500
	h.Upsert(flowLower)
	rev3 := h.Revision()
	if rev3 != rev2 {
		t.Fatalf("lower byte count should be ignored and not advance revision: rev2=%d, rev3=%d", rev2, rev3)
	}
}

func TestRevisionClosedAndClosedAtAdvance(t *testing.T) {
	now := time.UnixMilli(1_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	h.Upsert(connectionFlow{
		ID:        "flow-1",
		CreatedAt: now.UnixMilli(),
		Closed:    false,
		ClosedAt:  0,
	})
	rev0 := h.Revision()

	// Closed transitions false -> true
	closedAt := now.Add(2 * time.Second).UnixMilli()
	h.Upsert(connectionFlow{
		ID:        "flow-1",
		CreatedAt: now.UnixMilli(),
		Closed:    true,
		ClosedAt:  closedAt,
	})
	rev1 := h.Revision()
	if rev1 <= rev0 {
		t.Fatalf("closed transition must advance revision: rev0=%d, rev1=%d", rev0, rev1)
	}

	// ClosedAt change on already closed connection
	h.Upsert(connectionFlow{
		ID:        "flow-1",
		CreatedAt: now.UnixMilli(),
		Closed:    true,
		ClosedAt:  closedAt + 1000,
	})
	rev2 := h.Revision()
	if rev2 <= rev1 {
		t.Fatalf("ClosedAt advance must advance revision: rev1=%d, rev2=%d", rev1, rev2)
	}
}

func TestRevisionMetadataFieldChangesAdvance(t *testing.T) {
	fields := []struct {
		name   string
		update func(*connectionFlow)
	}{
		{"Domain", func(f *connectionFlow) { f.Domain = "updated.org" }},
		{"DestinationAddress", func(f *connectionFlow) { f.DestinationAddress = "8.8.8.8" }},
		{"DestinationPort", func(f *connectionFlow) { f.DestinationPort = 8080 }},
		{"PackageNames", func(f *connectionFlow) { f.PackageNames = "com.new.app" }},
		{"UID", func(f *connectionFlow) { f.UID = 10042 }},
		{"Network", func(f *connectionFlow) { f.Network = "udp" }},
		{"Chain", func(f *connectionFlow) { f.Chain = "a,b,c" }},
		{"LogicalOutbound", func(f *connectionFlow) { f.LogicalOutbound = "new-logical" }},
		{"FinalOutboundTag", func(f *connectionFlow) { f.FinalOutboundTag = "new-final" }},
		{"MatchedRuleText", func(f *connectionFlow) { f.MatchedRuleText = "rule-match-updated" }},
		{"OriginDestination", func(f *connectionFlow) { f.OriginDestination = "127.0.0.1:1234" }},
	}

	for _, tc := range fields {
		t.Run(tc.name, func(t *testing.T) {
			now := time.UnixMilli(2_000_000)
			h := newConnectionHistory(func() time.Time { return now })
			base := connectionFlow{
				ID:                 "flow-test",
				CreatedAt:          now.UnixMilli(),
				Domain:             "initial.org",
				DestinationAddress: "1.1.1.1",
				DestinationPort:    53,
				PackageNames:       "com.orig.app",
				UID:                10001,
				Network:            "tcp",
				Chain:              "x,y",
				LogicalOutbound:    "orig-logical",
				FinalOutboundTag:   "orig-final",
				MatchedRuleText:    "orig-rule",
				OriginDestination:  "127.0.0.1:5678",
			}
			h.Upsert(base)
			revBefore := h.Revision()

			updated := base
			tc.update(&updated)
			h.Upsert(updated)
			revAfter := h.Revision()

			if revAfter <= revBefore {
				t.Fatalf("field %s change failed to advance revision: before=%d, after=%d", tc.name, revBefore, revAfter)
			}
		})
	}
}

func TestRevisionExpiredFlowRemovalAdvances(t *testing.T) {
	now := time.UnixMilli(5_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	// Flow created 9 minutes ago (within 10m limit)
	h.Upsert(connectionFlow{
		ID:        "flow-expiring",
		CreatedAt: now.Add(-9 * time.Minute).UnixMilli(),
	})
	rev0 := h.Revision()

	// Advance time by 2 minutes: flow is now 11 minutes old (>10m limit)
	now = now.Add(2 * time.Minute)
	// Calling MergeLive with no trackers triggers expiration eviction
	rev1 := h.MergeLive(nil)
	if rev1 <= rev0 {
		t.Fatalf("expired flow removal must advance revision: rev0=%d, rev1=%d", rev0, rev1)
	}
	if len(h.Snapshot()) != 0 {
		t.Fatalf("expired flow must be pruned from snapshot")
	}
}

func TestRevisionCapacityEvictionAdvances(t *testing.T) {
	now := time.UnixMilli(6_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	// Fill up to max (300)
	for i := 0; i < 300; i++ {
		h.Upsert(connectionFlow{
			ID:        "flow-" + itoa(i),
			CreatedAt: now.UnixMilli() + int64(i),
		})
	}
	revFull := h.Revision()

	// Add 301st flow: triggers eviction of the oldest
	h.Upsert(connectionFlow{
		ID:        "flow-300",
		CreatedAt: now.UnixMilli() + 300,
	})
	revEvicted := h.Revision()
	if revEvicted <= revFull {
		t.Fatalf("capacity eviction (>300) must advance revision: revFull=%d, revEvicted=%d", revFull, revEvicted)
	}
	if len(h.Snapshot()) != 300 {
		t.Fatalf("snapshot size must remain capped at 300")
	}
}

func TestRevisionSnapshotDoesNotProduceFalseRevision(t *testing.T) {
	now := time.UnixMilli(7_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	h.Upsert(connectionFlow{
		ID:        "flow-snap",
		CreatedAt: now.UnixMilli(),
	})
	revBefore := h.Revision()

	_ = h.Snapshot()
	revAfter1 := h.Revision()
	if revAfter1 != revBefore {
		t.Fatalf("Snapshot() must not advance revision when no eviction: before=%d, after=%d", revBefore, revAfter1)
	}

	_ = h.SnapshotJSON()
	revAfter2 := h.Revision()
	if revAfter2 != revBefore {
		t.Fatalf("SnapshotJSON() must not advance revision when no eviction: before=%d, after=%d", revBefore, revAfter2)
	}
}

func TestRevisionMergeLiveTrackerByteUpdate(t *testing.T) {
	now := time.UnixMilli(8_000_000)
	h := newConnectionHistory(func() time.Time { return now })

	var upload atomic.Int64
	var download atomic.Int64
	upload.Store(1000)
	download.Store(2000)

	tracker := &trafficcontrol.TrackerMetadata{
		ID:        uuid.FromStringOrNil("44444444-4444-4444-4444-444444444444"),
		CreatedAt: now,
		Metadata: adapter.InboundContext{
			Destination: M.Socksaddr{Fqdn: "example.com", Port: 443},
		},
		Upload:   &upload,
		Download: &download,
	}

	src := &revisionFakeSource{conns: []*trafficcontrol.TrackerMetadata{tracker}}

	// First merge
	rev1 := h.MergeLive(src)
	if rev1 == 0 {
		t.Fatalf("expected non-zero revision after first merge, got %d", rev1)
	}

	// Second merge without byte change
	rev2 := h.MergeLive(src)
	if rev2 != rev1 {
		t.Fatalf("expected identical revision on unchanged tracker bytes, got rev1=%d, rev2=%d", rev1, rev2)
	}

	// Update bytes in tracker
	upload.Store(1500)
	rev3 := h.MergeLive(src)
	if rev3 <= rev2 {
		t.Fatalf("tracker byte increase must advance revision: rev2=%d, rev3=%d", rev2, rev3)
	}
}

func TestMergeAndSnapshotSince(t *testing.T) {
	now := time.UnixMilli(9_000_000)
	h := newConnectionHistory(func() time.Time { return now })

	var upload atomic.Int64
	upload.Store(100)
	tracker := &trafficcontrol.TrackerMetadata{
		ID:        uuid.FromStringOrNil("55555555-5555-5555-5555-555555555555"),
		CreatedAt: now,
		Metadata: adapter.InboundContext{
			Destination: M.Socksaddr{Fqdn: "example.org", Port: 443},
		},
		Upload: &upload,
	}
	src := &revisionFakeSource{conns: []*trafficcontrol.TrackerMetadata{tracker}}

	scansBefore := h.liveScans.Load()

	// Initial poll: lastRevision = -1 (force initial)
	resp1 := h.MergeAndSnapshotSince(src, -1)
	if resp1.Unchanged {
		t.Fatal("expected resp1.Unchanged to be false on initial fetch")
	}
	if resp1.Payload == nil || resp1.Payload.Value == "" {
		t.Fatal("expected non-empty payload on initial fetch")
	}
	rev1 := resp1.Revision
	if rev1 == 0 {
		t.Fatalf("expected non-zero revision, got %d", rev1)
	}
	if h.liveScans.Load()-scansBefore != 1 {
		t.Fatalf("expected exactly 1 live scan on changed tick, got %d", h.liveScans.Load()-scansBefore)
	}

	// Unchanged poll: lastRevision = rev1
	scansBefore = h.liveScans.Load()
	resp2 := h.MergeAndSnapshotSince(src, rev1)
	if !resp2.Unchanged {
		t.Fatal("expected resp2.Unchanged to be true when revision unchanged")
	}
	if resp2.Revision != rev1 {
		t.Fatalf("expected revision %d, got %d", rev1, resp2.Revision)
	}
	if h.liveScans.Load()-scansBefore != 1 {
		t.Fatalf("expected exactly 1 live scan on poll, got %d", h.liveScans.Load()-scansBefore)
	}

	// Changed poll: tracker updates
	upload.Store(200)
	scansBefore = h.liveScans.Load()
	resp3 := h.MergeAndSnapshotSince(src, rev1)
	if resp3.Unchanged {
		t.Fatal("expected resp3.Unchanged to be false after tracker update")
	}
	if resp3.Revision <= rev1 {
		t.Fatalf("expected revision to advance past %d, got %d", rev1, resp3.Revision)
	}
	if h.liveScans.Load()-scansBefore != 1 {
		t.Fatalf("expected exactly 1 live scan on changed tick, got %d", h.liveScans.Load()-scansBefore)
	}

	// Cache test: calling with lastRevision = -1 for the same revision reuses cached JSON
	respCached := h.MergeAndSnapshotSince(nil, -1)
	if respCached.Payload.Value != resp3.Payload.Value {
		t.Fatalf("expected cached JSON to match: got %s, want %s", respCached.Payload.Value, resp3.Payload.Value)
	}
}

type panickingSource struct{}

func (s *panickingSource) Connections() []*trafficcontrol.TrackerMetadata {
	panic("simulated concurrent closed panic")
}

func (s *panickingSource) ClosedConnections() []*trafficcontrol.TrackerMetadata {
	return nil
}

func TestMergeAndSnapshotSincePanicRecoveryAndDisposeTeardown(t *testing.T) {
	now := time.UnixMilli(9_000_000)
	h := newConnectionHistory(func() time.Time { return now })

	// Panic recovery test: panicking live connection source does not crash MergeAndSnapshotSince
	panicker := &panickingSource{}
	resp := h.MergeAndSnapshotSince(panicker, -1)
	if resp == nil {
		t.Fatal("expected non-nil response even when live source panics")
	}

	// Teardown test: Dispose clears cache and ID map
	h.Upsert(connectionFlow{ID: "f1", CreatedAt: now.UnixMilli()})
	_ = h.SnapshotJSON()
	if len(h.byID) == 0 {
		t.Fatal("expected non-empty byID before dispose")
	}
	h.Dispose()
	if h.byID != nil {
		t.Errorf("expected byID to be nil after Dispose, got len %d", len(h.byID))
	}
	if h.cachedJSON != "" || h.cachedRevision != 0 {
		t.Errorf("expected cache to be cleared after Dispose")
	}
}

func TestBoxInstanceClosedSnapshotBehavior(t *testing.T) {
	b := &BoxInstance{
		state: 2, // Closed
	}
	resp := b.ConnectionSnapshotSince(10)
	if resp == nil || !resp.Unchanged {
		t.Fatalf("expected unchanged response from closed box instance, got %+v", resp)
	}
	if rev := b.ConnectionSnapshotRevision(); rev != 0 {
		t.Fatalf("expected 0 revision from closed box instance, got %d", rev)
	}
	if snap := b.ConnectionSnapshot(); snap == nil || snap.Value != `{"flows":[]}` {
		t.Fatalf("expected empty flows snapshot from closed box instance, got %+v", snap)
	}
}


