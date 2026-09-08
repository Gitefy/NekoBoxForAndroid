package libcore

import (
	"testing"

	"github.com/sagernet/sing-box/option"
)

func TestExtractLocalGeoRuleSetsAcceptsMissingRoute(t *testing.T) {
	if err := extractLocalGeoRuleSets(option.Options{}); err != nil {
		t.Fatalf("missing route must not fail geo pre-processing: %v", err)
	}
}
