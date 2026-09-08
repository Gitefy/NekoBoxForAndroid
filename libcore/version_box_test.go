package libcore

import "testing"

func TestFormatVersionBoxIncludesCoreVersionAndRevision(t *testing.T) {
	got := formatVersionBox("1.15.0", "05f7bc1aac0cc7297259249142481f8a6c1a0d73")
	if got == "" || !containsAll(got, "sing-box: 1.15.0", "revision: 05f7bc1aac0cc7297259249142481f8a6c1a0d73") {
		t.Fatalf("version output lost traceable core metadata: %q", got)
	}
}

func containsAll(value string, parts ...string) bool {
	for _, part := range parts {
		if !contains(value, part) {
			return false
		}
	}
	return true
}

func contains(value, part string) bool {
	for i := 0; i+len(part) <= len(value); i++ {
		if value[i:i+len(part)] == part {
			return true
		}
	}
	return false
}
