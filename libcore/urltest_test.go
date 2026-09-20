package libcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type mockDialer struct {
	dialCount atomic.Int32
	lastAddr  atomic.Pointer[string]
	dialFunc  func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error)
}

var _ N.Dialer = (*mockDialer)(nil)

func (d *mockDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.dialCount.Add(1)
	addrStr := destination.String()
	d.lastAddr.Store(&addrStr)
	if d.dialFunc != nil {
		return d.dialFunc(ctx, network, destination)
	}
	var std net.Dialer
	return std.DialContext(ctx, network, destination.String())
}

func (d *mockDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("packet not supported in mock dialer")
}

func TestFastRTTMeasurement(t *testing.T) {
	var requestCount atomic.Int32
	const serverDelay = 60 * time.Millisecond

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count := requestCount.Add(1)
		if count == 1 {
			// Warm-up request: fast response
			w.WriteHeader(http.StatusNoContent)
			return
		}
		// Second (measured) request: controlled delay
		time.Sleep(serverDelay)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	dialer := &mockDialer{}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	latency, err := urlTestRTT(ctx, ts.URL, dialer)
	if err != nil {
		t.Fatalf("urlTestRTT failed: %v", err)
	}

	if requestCount.Load() != 2 {
		t.Fatalf("expected exactly 2 requests, got %d", requestCount.Load())
	}

	// Latency must reflect the second request delay (around 60ms) and not cold-start handshakes
	if latency < 45 || latency > 300 {
		t.Fatalf("expected latency around 60ms, got %d ms", latency)
	}
}

func TestFastRTTTwoRequestsAndKeepAliveReuse(t *testing.T) {
	var tcpConnCount atomic.Int32
	var httpReqCount atomic.Int32

	// Custom listener to count underlying TCP connections accepted by the server
	rawListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("net.Listen failed: %v", err)
	}

	trackedListener := &connectionTrackingListener{
		Listener:  rawListener,
		connCount: &tcpConnCount,
	}

	ts := &httptest.Server{
		Listener: trackedListener,
		Config: &http.Server{
			Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				httpReqCount.Add(1)
				w.WriteHeader(http.StatusNoContent)
			}),
		},
	}
	ts.Start()
	defer ts.Close()

	dialer := &mockDialer{}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err = urlTestRTT(ctx, ts.URL, dialer)
	if err != nil {
		t.Fatalf("urlTestRTT failed: %v", err)
	}

	if httpReqCount.Load() != 2 {
		t.Fatalf("expected exactly 2 HTTP requests, got %d", httpReqCount.Load())
	}

	// Two requests must reuse the same single underlying TCP connection
	if tcpConnCount.Load() != 1 {
		t.Fatalf("expected exactly 1 underlying TCP connection (Keep-Alive reuse), got %d", tcpConnCount.Load())
	}
	if dialer.dialCount.Load() != 1 {
		t.Fatalf("expected dialer to be called exactly 1 time due to connection reuse, got %d", dialer.dialCount.Load())
	}
}

type connectionTrackingListener struct {
	net.Listener
	connCount *atomic.Int32
}

func (l *connectionTrackingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err == nil {
		l.connCount.Add(1)
	}
	return c, err
}

func TestFastRTTTimeout(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	dialer := &mockDialer{}
	// Context timeout shorter than server response
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	_, err := urlTestRTT(ctx, ts.URL, dialer)
	if err == nil {
		t.Fatal("expected timeout error, got nil")
	}
	if !errors.Is(err, context.DeadlineExceeded) && !errors.Is(ctx.Err(), context.DeadlineExceeded) {
		t.Fatalf("expected DeadlineExceeded error, got %v", err)
	}
}

func TestFastRTTCancellation(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	dialer := &mockDialer{}
	ctx, cancel := context.WithCancel(context.Background())
	// Cancel immediately
	cancel()

	_, err := urlTestRTT(ctx, ts.URL, dialer)
	if err == nil {
		t.Fatal("expected cancellation error, got nil")
	}
	if !errors.Is(err, context.Canceled) && !errors.Is(ctx.Err(), context.Canceled) {
		t.Fatalf("expected Canceled error, got %v", err)
	}
}

func TestFastRTTHttpStatusAndRedirect(t *testing.T) {
	for _, tc := range []struct {
		name       string
		statusCode int
		handler    http.HandlerFunc
	}{
		{
			name:       "204 No Content",
			statusCode: http.StatusNoContent,
			handler: func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusNoContent)
			},
		},
		{
			name:       "200 OK",
			statusCode: http.StatusOK,
			handler: func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte("ok"))
			},
		},
		{
			name:       "302 Found (Stop redirect)",
			statusCode: http.StatusFound,
			handler: func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", "/destination")
				w.WriteHeader(http.StatusFound)
			},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ts := httptest.NewServer(tc.handler)
			defer ts.Close()

			dialer := &mockDialer{}
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()

			latency, err := urlTestRTT(ctx, ts.URL, dialer)
			if err != nil {
				t.Fatalf("urlTestRTT failed for status %d: %v", tc.statusCode, err)
			}
			if latency < 0 {
				t.Fatalf("expected non-negative latency, got %d", latency)
			}
		})
	}
}

func TestFastRTTDialerCorrectness(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	dialerCalled := false
	dialer := &mockDialer{
		dialFunc: func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
			dialerCalled = true
			if network != "tcp" {
				t.Errorf("expected network 'tcp', got %q", network)
			}
			var std net.Dialer
			return std.DialContext(ctx, network, destination.String())
		},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := urlTestRTT(ctx, ts.URL, dialer)
	if err != nil {
		t.Fatalf("urlTestRTT failed: %v", err)
	}

	if !dialerCalled {
		t.Fatal("expected mock dialer to be called, but it was bypassed")
	}

	// Test dialer failure must return error immediately without fallback
	failingDialer := &mockDialer{
		dialFunc: func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
			return nil, errors.New("simulated dial failure")
		},
	}

	_, err = urlTestRTT(ctx, ts.URL, failingDialer)
	if err == nil || err.Error() != "simulated dial failure" && !errors.Is(err, errors.New("simulated dial failure")) {
		if err == nil {
			t.Fatal("expected simulated dial failure, got nil")
		}
	}
}

func TestFastRTTIntegrationWithSingBoxInstance(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer ts.Close()

	box, err := NewSingBoxInstance(urlTestTargetConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer box.Close()

	latency, err := UrlTest(box, ts.URL, 3000)
	if err != nil {
		t.Fatalf("UrlTest failed: %v", err)
	}
	if latency < 0 {
		t.Fatalf("invalid latency: %d", latency)
	}

	latencyTarget, err := UrlTestWithTarget(box, ts.URL, 3000, "target")
	if err != nil {
		t.Fatalf("UrlTestWithTarget failed: %v", err)
	}
	if latencyTarget < 0 {
		t.Fatalf("invalid latency: %d", latencyTarget)
	}
}
