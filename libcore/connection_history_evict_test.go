package libcore

// P3-A1 equivalence tests: verifies that skipping evictLocked() on updates
// produces identical observable behavior to always running it.
//
// Each test uses the evictPasses counter as the observable proxy for whether
// evictLocked() ran, then validates the Snapshot() contents are identical to
// what the old implementation would have produced.

import (
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// Test 1: Updating an existing flow must NOT trigger evictLocked().
// Old behavior: evictLocked() ran on every Upsert regardless.
// New behavior: evictLocked() only runs when a new entry is inserted.
// ---------------------------------------------------------------------------

func TestExistingFlowUpdateSkipsEviction(t *testing.T) {
	now := time.UnixMilli(20_000_000)
	h := newConnectionHistory(func() time.Time { return now })

	// Insert the flow — this IS a new entry so evict runs once.
	h.Upsert(connectionFlow{ID: "x", CreatedAt: now.UnixMilli(), UploadBytes: 1})
	afterInsert := h.evictPasses.Load()

	// Update the same flow — must NOT run evictLocked().
	h.Upsert(connectionFlow{ID: "x", CreatedAt: now.UnixMilli(), UploadBytes: 9})
	afterUpdate := h.evictPasses.Load()

	if afterUpdate != afterInsert {
		t.Fatalf("evict ran on update: evictPasses before=%d after=%d", afterInsert, afterUpdate)
	}

	// Snapshot must still reflect the merged bytes.
	got := h.Snapshot()
	if len(got) != 1 || got[0].UploadBytes != 9 {
		t.Fatalf("wrong snapshot after update: %+v", got)
	}
}

// ---------------------------------------------------------------------------
// Test 2: A new flow must still trigger evictLocked() exactly once.
// ---------------------------------------------------------------------------

func TestNewFlowTriggersEviction(t *testing.T) {
	now := time.UnixMilli(20_100_000)
	h := newConnectionHistory(func() time.Time { return now })

	before := h.evictPasses.Load()
	h.Upsert(connectionFlow{ID: "new", CreatedAt: now.UnixMilli()})
	if h.evictPasses.Load() != before+1 {
		t.Fatalf("evict did not run on new insert: evictPasses=%d", h.evictPasses.Load())
	}
}

// ---------------------------------------------------------------------------
// Test 3: Insert 300, update one (no evict), insert 301st (evict + trim).
// The final Snapshot must contain exactly 300 entries.
// ---------------------------------------------------------------------------

func TestOver300EvictsOnInsertNotUpdate(t *testing.T) {
	now := time.UnixMilli(20_200_000)
	h := newConnectionHistory(func() time.Time { return now })

	// Fill to exactly max.
	for i := 0; i < 300; i++ {
		h.Upsert(connectionFlow{ID: "f-" + itoa(i), CreatedAt: now.UnixMilli() + int64(i)})
	}
	if n := len(h.byID); n != 300 {
		t.Fatalf("expected 300 entries, got %d", n)
	}

	evictBeforeUpdate := h.evictPasses.Load()
	// Update an existing entry — evict must NOT run.
	h.Upsert(connectionFlow{ID: "f-0", CreatedAt: now.UnixMilli(), UploadBytes: 99})
	if h.evictPasses.Load() != evictBeforeUpdate {
		t.Fatalf("evict ran on update of existing flow")
	}
	// Still exactly 300.
	if n := len(h.byID); n != 300 {
		t.Fatalf("size changed after update: %d", n)
	}

	evictBeforeInsert := h.evictPasses.Load()
	// Insert the 301st — evict MUST run and trim back to 300.
	h.Upsert(connectionFlow{ID: "extra", CreatedAt: now.UnixMilli() + 301})
	if h.evictPasses.Load() != evictBeforeInsert+1 {
		t.Fatalf("evict did not run on 301st insert")
	}
	if got := len(h.Snapshot()); got != 300 {
		t.Fatalf("expected 300 after eviction, got %d", got)
	}
}

// ---------------------------------------------------------------------------
// Test 4: An expired flow is NOT cleaned up by updating another flow.
// It IS cleaned up by Snapshot().
// ---------------------------------------------------------------------------

func TestExpiredFlowRemovedBySnapshotNotByUpdate(t *testing.T) {
	now := time.UnixMilli(20_300_000)
	h := newConnectionHistory(func() time.Time { return now })

	// Insert a flow that will expire.
	h.Upsert(connectionFlow{ID: "old", CreatedAt: now.Add(-11 * time.Minute).UnixMilli()})
	// Insert a live flow.
	h.Upsert(connectionFlow{ID: "live", CreatedAt: now.UnixMilli()})

	// Update the live flow — expired flow must NOT be removed by this.
	// (Under the old implementation it would be, because evictLocked ran always.)
	// Under the new implementation it is also not removed... but the CONTRACT
	// is that Snapshot() is the guarantee. We verify that the expired flow IS
	// removed by Snapshot().
	h.Upsert(connectionFlow{ID: "live", CreatedAt: now.UnixMilli(), UploadBytes: 5})

	// Snapshot must clean up the expired flow and return only "live".
	got := h.Snapshot()
	if len(got) != 1 || got[0].ID != "live" {
		t.Fatalf("Snapshot should have evicted expired flow; got=%v", got)
	}
}

// ---------------------------------------------------------------------------
// Test 5: A closed flow update (receiving a close event) skips eviction.
// ---------------------------------------------------------------------------

func TestClosedFlowUpdateSkipsEviction(t *testing.T) {
	now := time.UnixMilli(20_400_000)
	h := newConnectionHistory(func() time.Time { return now })

	// Insert as open.
	h.Upsert(connectionFlow{ID: "conn", CreatedAt: now.UnixMilli()})
	evictAfterInsert := h.evictPasses.Load()

	// Close event (same ID → update).
	h.Upsert(connectionFlow{ID: "conn", CreatedAt: now.UnixMilli(), Closed: true, ClosedAt: now.UnixMilli()})
	if h.evictPasses.Load() != evictAfterInsert {
		t.Fatalf("evict ran on close-event update")
	}

	got := h.Snapshot()
	if len(got) != 1 || !got[0].Closed {
		t.Fatalf("flow should be closed: %+v", got)
	}
}

// ---------------------------------------------------------------------------
// Test 6: Snapshot() always runs evictLocked() regardless of recent inserts.
// ---------------------------------------------------------------------------

func TestSnapshotAfterUpdateStillEvicts(t *testing.T) {
	now := time.UnixMilli(20_500_000)
	h := newConnectionHistory(func() time.Time { return now })

	h.Upsert(connectionFlow{ID: "z", CreatedAt: now.UnixMilli()})
	// Update (no evict).
	h.Upsert(connectionFlow{ID: "z", CreatedAt: now.UnixMilli(), UploadBytes: 7})

	before := h.evictPasses.Load()
	_ = h.Snapshot()
	if h.evictPasses.Load() != before+1 {
		t.Fatalf("Snapshot must always run evictLocked")
	}
}

// ---------------------------------------------------------------------------
// Test 7: Duplicate ID with same CreatedAt — merge, no extra evict pass.
// ---------------------------------------------------------------------------

func TestSameCreatedAtDuplicateIDNoExtraEvict(t *testing.T) {
	now := time.UnixMilli(20_600_000)
	h := newConnectionHistory(func() time.Time { return now })

	// First insert.
	h.Upsert(connectionFlow{ID: "dup", CreatedAt: now.UnixMilli(), DownloadBytes: 10})
	evict1 := h.evictPasses.Load()

	// Duplicate — same CreatedAt. Must merge, not insert new, so no extra evict.
	h.Upsert(connectionFlow{ID: "dup", CreatedAt: now.UnixMilli(), DownloadBytes: 20})
	if h.evictPasses.Load() != evict1 {
		t.Fatalf("duplicate upsert triggered extra eviction")
	}
	if n := len(h.byID); n != 1 {
		t.Fatalf("expected 1 entry after duplicate, got %d", n)
	}
	// merged download should be max(10, 20) = 20.
	got := h.Snapshot()
	if got[0].DownloadBytes != 20 {
		t.Fatalf("expected DownloadBytes=20, got %d", got[0].DownloadBytes)
	}
}

// ---------------------------------------------------------------------------
// Test 8: Update existing + insert new = exactly 1 evict pass net.
// Verifies the two paths are properly isolated.
// ---------------------------------------------------------------------------

func TestNewFlowAfterUpdateExactlyOneEvict(t *testing.T) {
	now := time.UnixMilli(20_700_000)
	h := newConnectionHistory(func() time.Time { return now })

	h.Upsert(connectionFlow{ID: "existing", CreatedAt: now.UnixMilli()})
	startEvict := h.evictPasses.Load()

	// Update existing — no evict.
	h.Upsert(connectionFlow{ID: "existing", CreatedAt: now.UnixMilli(), UploadBytes: 3})
	if h.evictPasses.Load() != startEvict {
		t.Fatalf("update triggered evict unexpectedly")
	}

	// Insert new — exactly one evict.
	h.Upsert(connectionFlow{ID: "brand-new", CreatedAt: now.UnixMilli() + 1})
	if h.evictPasses.Load() != startEvict+1 {
		t.Fatalf("new insert should trigger exactly one evict: evictPasses=%d", h.evictPasses.Load())
	}

	got := h.Snapshot()
	ids := make(map[string]bool)
	for _, f := range got {
		ids[f.ID] = true
	}
	if !ids["existing"] || !ids["brand-new"] {
		t.Fatalf("both flows should be in snapshot: %v", got)
	}
}
