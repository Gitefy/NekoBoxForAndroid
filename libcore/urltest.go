package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
)

// urlTestRTT performs a fast RTT-style HTTP connectivity probe through the provided detour.
// It executes a warm-up request to establish the proxy protocol handshake, TCP, TLS, and
// connection pool, then a second request reusing the persistent connection to measure
// round-trip time between WroteHeaders and GotFirstResponseByte.
func urlTestRTT(ctx context.Context, link string, detour N.Dialer) (int32, error) {
	if detour == nil {
		return 0, errors.New("nil detour dialer")
	}
	if link == "" {
		link = "https://www.gstatic.com/generate_204"
	}
	if _, err := url.Parse(link); err != nil {
		return 0, err
	}

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
		DisableKeepAlives:   false,
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: constant.TCPTimeout,
		TLSClientConfig: &tls.Config{
			Time:    ntp.TimeFuncFromContext(ctx),
			RootCAs: adapter.RootPoolFromContext(ctx),
		},
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	// Request 1: Warm-up
	req1, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	resp1, err := client.Do(req1)
	if err != nil {
		return 0, err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp1.Body, 4096))
	resp1.Body.Close()

	// Request 2: Measured RTT
	req2, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}

	var (
		wroteHeaders time.Time
		gotFirstByte time.Time
	)

	trace := &httptrace.ClientTrace{
		WroteHeaders: func() {
			wroteHeaders = time.Now()
		},
		GotFirstResponseByte: func() {
			gotFirstByte = time.Now()
		},
	}
	req2 = req2.WithContext(httptrace.WithClientTrace(req2.Context(), trace))

	req2Start := time.Now()
	resp2, err := client.Do(req2)
	req2End := time.Now()
	if err != nil {
		return 0, err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp2.Body, 4096))
	resp2.Body.Close()

	var latency time.Duration
	if !gotFirstByte.IsZero() && !wroteHeaders.IsZero() && !gotFirstByte.Before(wroteHeaders) {
		latency = gotFirstByte.Sub(wroteHeaders)
	} else if !wroteHeaders.IsZero() && !req2End.Before(wroteHeaders) {
		// Fallback: GotFirstResponseByte not triggered, use header write to response finish
		latency = req2End.Sub(wroteHeaders)
	} else {
		// Fallback: full second request duration
		latency = req2End.Sub(req2Start)
	}

	return int32(latency.Milliseconds()), nil
}
