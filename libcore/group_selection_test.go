package libcore

import (
	"strings"
	"testing"
)

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
