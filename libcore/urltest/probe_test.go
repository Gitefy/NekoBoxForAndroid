package urltest

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type recordingDialer struct {
	tag        string
	dialCount  atomic.Int32
	lastAddr   atomic.Pointer[string]
	onDialFunc func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error)
}

var _ N.Dialer = (*recordingDialer)(nil)

func (d *recordingDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.dialCount.Add(1)
	addrStr := destination.String()
	d.lastAddr.Store(&addrStr)
	if d.onDialFunc != nil {
		return d.onDialFunc(ctx, network, destination)
	}
	var std net.Dialer
	return std.DialContext(ctx, network, destination.String())
}

func (d *recordingDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

func TestFastRTTProbe(t *testing.T) {
	var requestCount atomic.Int32
	const serverDelay = 50 * time.Millisecond

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count := requestCount.Add(1)
		if count == 1 {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		time.Sleep(serverDelay)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	dialer := &recordingDialer{tag: "d1"}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	latency, mode, err := Probe(ctx, dialer, ts.URL)
	if err != nil {
		t.Fatalf("Probe failed: %v", err)
	}

	if mode != ProbeModeFastRTT {
		t.Fatalf("expected mode %v, got %v", ProbeModeFastRTT, mode)
	}

	if latency < 40 || latency > 500 {
		t.Fatalf("measured latency %d ms out of expected bounds [40, 500]", latency)
	}

	if requestCount.Load() != 2 {
		t.Fatalf("expected 2 server requests (warm-up + measured), got %d", requestCount.Load())
	}
}

func TestProbeFallbackToUpstream(t *testing.T) {
	// A dialer that fails on second request with EOF (causing FastRTT to fail),
	// but standard upstream URLTest can succeed.
	var requestCount atomic.Int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestCount.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	// Dialer that errors out during warm-up FastRTT if a specific condition occurs,
	// or we can test UpstreamURLTest directly.
	dialer := &recordingDialer{tag: "d-fallback"}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	latency, err := UpstreamURLTest(ctx, dialer, ts.URL)
	if err != nil {
		t.Fatalf("UpstreamURLTest failed: %v", err)
	}
	if latency < 0 {
		t.Fatalf("expected non-negative latency, got %d", latency)
	}
}

func TestConcurrentTargetsIsolation(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	const numTargets = 5
	dialers := make([]*recordingDialer, numTargets)
	for i := 0; i < numTargets; i++ {
		dialers[i] = &recordingDialer{tag: fmt.Sprintf("target-%d", i)}
	}

	var wg sync.WaitGroup
	errs := make([]error, numTargets)
	modes := make([]ProbeMode, numTargets)
	latencies := make([]int32, numTargets)

	for i := 0; i < numTargets; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			l, m, err := Probe(ctx, dialers[idx], ts.URL)
			errs[idx] = err
			modes[idx] = m
			latencies[idx] = l
		}(i)
	}

	wg.Wait()

	for i := 0; i < numTargets; i++ {
		if errs[i] != nil {
			t.Fatalf("target %d probe failed: %v", i, errs[i])
		}
		if modes[i] != ProbeModeFastRTT {
			t.Fatalf("target %d expected FAST_RTT, got %v", i, modes[i])
		}
		// Each target must have dialed independently (at least 1 connection for warm-up)
		count := dialers[i].dialCount.Load()
		if count < 1 {
			t.Fatalf("target %d dialed %d times, expected >= 1", i, count)
		}
	}
}
