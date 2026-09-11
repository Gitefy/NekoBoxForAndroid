package libcore

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestNewConnectionAppearsInSnapshot(t *testing.T) {
	now := time.UnixMilli(1_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	h.Upsert(connectionFlow{ID: "a", CreatedAt: now.UnixMilli(), Domain: "youtube.com", FinalOutboundTag: "node-1"})
	got := h.Snapshot()
	if len(got) != 1 || got[0].ID != "a" || got[0].Domain != "youtube.com" {
		t.Fatalf("snapshot=%v", got)
	}
}

func TestCloseKeepsFlowUntilAgeExpiry(t *testing.T) {
	now := time.UnixMilli(2_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	created := now.Add(-5 * time.Minute).UnixMilli()
	h.Upsert(connectionFlow{ID: "c", CreatedAt: created, Closed: true, ClosedAt: now.UnixMilli()})
	if len(h.Snapshot()) != 1 {
		t.Fatalf("closed flow should remain within 10m")
	}
	now = now.Add(6 * time.Minute)
	if len(h.Snapshot()) != 0 {
		t.Fatalf("closed flow should expire after 10m from createdAt")
	}
}

func TestHistoryEvictsBeyond300(t *testing.T) {
	now := time.UnixMilli(3_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	for i := 0; i < 305; i++ {
		h.Upsert(connectionFlow{ID: string(rune('A'+i%26)) + "-" + itoa(i), CreatedAt: now.UnixMilli() + int64(i)})
	}
	if got := len(h.Snapshot()); got != 300 {
		t.Fatalf("want 300 got %d", got)
	}
}

func TestHistoryEvictsOlderThan10Minutes(t *testing.T) {
	now := time.UnixMilli(4_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	h.Upsert(connectionFlow{ID: "old", CreatedAt: now.Add(-11 * time.Minute).UnixMilli()})
	h.Upsert(connectionFlow{ID: "new", CreatedAt: now.UnixMilli()})
	got := h.Snapshot()
	if len(got) != 1 || got[0].ID != "new" {
		t.Fatalf("got %v", got)
	}
}

func TestSameConnectionIdUpdatesInsteadOfDuplicating(t *testing.T) {
	now := time.UnixMilli(5_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	h.Upsert(connectionFlow{ID: "x", CreatedAt: now.UnixMilli(), UploadBytes: 1})
	h.Upsert(connectionFlow{ID: "x", CreatedAt: now.UnixMilli(), UploadBytes: 9, Closed: true, ClosedAt: now.UnixMilli()})
	got := h.Snapshot()
	if len(got) != 1 || got[0].UploadBytes != 9 || !got[0].Closed {
		t.Fatalf("got %v", got)
	}
}

func TestSnapshotContainsLogicalAndFinalOutbound(t *testing.T) {
	now := time.UnixMilli(6_000_000)
	h := newConnectionHistory(func() time.Time { return now })
	h.Upsert(connectionFlow{
		ID:               "y",
		CreatedAt:        now.UnixMilli(),
		Chain:            "us-la-03,router-us",
		LogicalOutbound:  "router-us",
		FinalOutboundTag: "us-la-03",
	})
	raw := h.SnapshotJSON()
	var env connectionSnapshotEnvelope
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		t.Fatal(err)
	}
	if len(env.Flows) != 1 {
		t.Fatalf("flows=%v", env.Flows)
	}
	flow := env.Flows[0]
	if flow.LogicalOutbound != "router-us" || flow.FinalOutboundTag != "us-la-03" {
		t.Fatalf("logical/final mismatch: %+v", flow)
	}
	if flow.LogicalOutbound == flow.FinalOutboundTag {
		t.Fatal("logical and final must stay distinct")
	}
}

func TestDisposeUnsubscribesWithoutLeak(t *testing.T) {
	unsub := 0
	h := newConnectionHistory(time.Now)
	h.unsub = func() { unsub++ }
	h.Dispose()
	h.Dispose()
	if unsub != 1 {
		t.Fatalf("unsub=%d", unsub)
	}
	if !h.stopped {
		t.Fatal("should be stopped")
	}
}

func TestBoundStringTruncatesRuleText(t *testing.T) {
	long := strings.Repeat("r", boundRuleText+40)
	if got := boundString(long, boundRuleText); len([]rune(got)) != boundRuleText {
		t.Fatalf("len=%d", len([]rune(got)))
	}
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	var buf [16]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}
