package urltest

import (
	"errors"
	"fmt"

	"github.com/sagernet/sing-box/adapter"
	N "github.com/sagernet/sing/common/network"
)

// OutboundAccessor abstracts the outbound manager lookup from a sing-box instance.
type OutboundAccessor interface {
	Outbound() adapter.OutboundManager
}

// ResolveTargetDialer resolves a dialer for the targetTag from the outbound manager.
// If targetTag is empty, it returns the configuration's declared default outbound.
// If the accessor is nil and targetTag is empty, it falls back to the system dialer.
func ResolveTargetDialer(accessor OutboundAccessor, targetTag string) (N.Dialer, error) {
	if accessor == nil {
		if targetTag != "" {
			return nil, fmt.Errorf("URL test target %q requires an active box instance", targetTag)
		}
		return N.SystemDialer, nil
	}

	mgr := accessor.Outbound()
	if mgr == nil {
		return nil, errors.New("URL test instance has no outbound manager")
	}

	if targetTag != "" {
		outbound, loaded := mgr.Outbound(targetTag)
		if !loaded {
			return nil, fmt.Errorf("URL test target %q not found", targetTag)
		}
		return outbound, nil
	}

	if outbound := mgr.Default(); outbound != nil {
		return outbound, nil
	}

	return nil, errors.New("URL test configuration has no default outbound")
}
