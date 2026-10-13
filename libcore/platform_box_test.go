package libcore

import (
	"net/netip"
	"testing"

	tun "github.com/sagernet/sing-tun"
)

func TestTunAddressesFromOptionsIncludesIPv4AndIPv6(t *testing.T) {
	options := &tun.Options{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		Inet6Address: []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
	}

	got := tunAddressesFromOptions(options)
	want := []netip.Addr{
		netip.MustParseAddr("172.19.0.1"),
		netip.MustParseAddr("fdfe:dcba:9876::1"),
	}
	if len(got) != len(want) {
		t.Fatalf("addresses=%v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("addresses=%v, want %v", got, want)
		}
	}
}
