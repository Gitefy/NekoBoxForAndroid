package libcore

import (
	"context"
	"libcore/urltest"

	N "github.com/sagernet/sing/common/network"
)

// urlTestRTT delegates to urltest.FastRTT to maintain backward compatibility
// with existing tests in the libcore package.
func urlTestRTT(ctx context.Context, link string, detour N.Dialer) (int32, error) {
	return urltest.FastRTT(ctx, detour, link)
}
