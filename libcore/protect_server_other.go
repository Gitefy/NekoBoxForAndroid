//go:build !unix

package libcore

import (
	"errors"
	"io"
)

func serveProtect(_ string, _ func(int) error) (io.Closer, error) {
	return nil, errors.New("socket protection is not supported on this platform")
}
