package libcore

import (
	"testing"
)

func TestBoxClosePropagatesUnderlyingFailureAndRemainsIdempotent(t *testing.T) {
	b, err := NewSingBoxInstance(urlTestTargetConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = b.Close(); err != nil {
		t.Fatalf("initial close failed: %v", err)
	}
	if err = b.Close(); err != nil {
		t.Fatalf("wrapper double close failed: %v", err)
	}
}
