package urltest

import (
	"context"
	"errors"
	"net"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type dummyDialer struct {
	tag string
}

func (d *dummyDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("not implemented")
}

func (d *dummyDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

func (d *dummyDialer) Type() string { return "dummy" }
func (d *dummyDialer) Tag() string  { return d.tag }
func (d *dummyDialer) Start(stage adapter.StartStage) error { return nil }
func (d *dummyDialer) Close() error { return nil }
func (d *dummyDialer) Network() []string { return []string{N.NetworkTCP} }
func (d *dummyDialer) Dependencies() []string { return nil }

type mockOutboundManager struct {
	adapter.OutboundManager
	outbounds map[string]adapter.Outbound
	def       adapter.Outbound
}

func (m *mockOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	o, found := m.outbounds[tag]
	return o, found
}

func (m *mockOutboundManager) Default() adapter.Outbound {
	return m.def
}

type mockAccessor struct {
	mgr adapter.OutboundManager
}

func (a *mockAccessor) Outbound() adapter.OutboundManager {
	return a.mgr
}

func TestResolveTargetDialer(t *testing.T) {
	t.Run("nil accessor empty tag returns system dialer", func(t *testing.T) {
		dialer, err := ResolveTargetDialer(nil, "")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if dialer != N.SystemDialer {
			t.Fatalf("expected system dialer, got %v", dialer)
		}
	})

	t.Run("nil accessor non-empty tag returns error", func(t *testing.T) {
		_, err := ResolveTargetDialer(nil, "target-1")
		if err == nil {
			t.Fatal("expected error, got nil")
		}
	})

	t.Run("explicit tag resolves matching outbound", func(t *testing.T) {
		d1 := &dummyDialer{tag: "node-1"}
		d2 := &dummyDialer{tag: "node-2"}
		mgr := &mockOutboundManager{
			outbounds: map[string]adapter.Outbound{
				"node-1": d1,
				"node-2": d2,
			},
		}
		accessor := &mockAccessor{mgr: mgr}

		dialer, err := ResolveTargetDialer(accessor, "node-2")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if dialer != N.Dialer(d2) {
			t.Fatalf("expected d2, got %v", dialer)
		}
	})

	t.Run("unknown tag returns error", func(t *testing.T) {
		mgr := &mockOutboundManager{
			outbounds: map[string]adapter.Outbound{},
		}
		accessor := &mockAccessor{mgr: mgr}

		_, err := ResolveTargetDialer(accessor, "missing")
		if err == nil {
			t.Fatal("expected error for missing target")
		}
	})

	t.Run("empty tag resolves default outbound", func(t *testing.T) {
		def := &dummyDialer{tag: "default-node"}
		mgr := &mockOutboundManager{
			def: def,
		}
		accessor := &mockAccessor{mgr: mgr}

		dialer, err := ResolveTargetDialer(accessor, "")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if dialer != N.Dialer(def) {
			t.Fatalf("expected default dialer, got %v", dialer)
		}
	})

	t.Run("empty tag without default returns error", func(t *testing.T) {
		mgr := &mockOutboundManager{
			def: nil,
		}
		accessor := &mockAccessor{mgr: mgr}

		_, err := ResolveTargetDialer(accessor, "")
		if err == nil {
			t.Fatal("expected error when no default outbound exists")
		}
	})
}
