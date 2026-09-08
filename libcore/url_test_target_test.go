package libcore

import (
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

const urlTestTargetConfig = `{
  "log": {"level": "info"},
  "outbounds": [
    {"type": "direct", "tag": "first"},
    {"type": "direct", "tag": "target"}
  ],
  "route": {"final": "target"}
}`

func TestURLTestDetourUsesExplicitTargetOrConfiguredDefault(t *testing.T) {
	box, err := NewSingBoxInstance(urlTestTargetConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer box.Close()

	for _, target := range []struct {
		name string
		tag  string
		want string
	}{
		{name: "explicit", tag: "target", want: "target"},
		{name: "explicit overrides final", tag: "first", want: "first"},
		{name: "configured default", tag: "", want: "target"},
	} {
		t.Run(target.name, func(t *testing.T) {
			dialer, err := urlTestDetourWithTarget(box, target.tag)
			if err != nil {
				t.Fatal(err)
			}
			outbound, ok := dialer.(adapter.Outbound)
			if !ok || outbound.Tag() != target.want {
				t.Fatalf("target %q resolved to %#v, want outbound %q", target.tag, dialer, target.want)
			}
		})
	}
}

func TestURLTestDetourRejectsUnknownExplicitTarget(t *testing.T) {
	box, err := NewSingBoxInstance(urlTestTargetConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer box.Close()

	if _, err = urlTestDetourWithTarget(box, "missing"); err == nil {
		t.Fatal("explicit URL test target must not fall back when it is missing")
	}
}
