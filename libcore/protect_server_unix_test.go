//go:build unix

package libcore

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestProtectServerWaitsForFDAndReportsResult(t *testing.T) {
	dir, err := os.MkdirTemp("", "egox-protect-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "protect")
	var calls atomic.Int32
	var reject atomic.Bool
	server, err := serveProtect(path, func(fd int) error {
		if _, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE); err != nil {
			return err
		}
		calls.Add(1)
		if reject.Load() {
			return errors.New("protection failed")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(time.Second))
	// Force the receiver to start before the descriptor arrives.
	time.Sleep(50 * time.Millisecond)
	if _, _, err := conn.WriteMsgUnix([]byte{1}, unix.UnixRights(fd), nil); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 1)
	if n, err := conn.Read(reply); err != nil || n != 1 || reply[0] != 1 {
		t.Fatalf("delayed FD not protected: n=%d reply=%v err=%v", n, reply, err)
	}
	var wg sync.WaitGroup
	for k := 0; k < 32; k++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := sendFdToProtect(fd, path); err != nil {
				t.Errorf("concurrent protection: %v", err)
			}
		}()
	}
	wg.Wait()
	if calls.Load() != 33 {
		t.Fatalf("protection calls=%d", calls.Load())
	}
	reject.Store(true)
	if err := sendFdToProtect(fd, path); err == nil {
		t.Fatal("failed protection acknowledged as success")
	}
	conn2, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer conn2.Close()
	_ = conn2.SetDeadline(time.Now().Add(time.Second))
	_, _ = conn2.Write([]byte{1})
	if n, err := conn2.Read(reply); err != nil || n != 1 || reply[0] != 0 {
		t.Fatalf("missing FD not rejected: n=%d reply=%v err=%v", n, reply, err)
	}
	if calls.Load() != 34 {
		t.Fatal("invalid request reached protection callback")
	}
}

type protectionTestPlatform struct {
	BoxPlatformInterface
	err error
}

func (p protectionTestPlatform) AutoDetectInterfaceControl(_ int32) error { return p.err }

func TestUIProtectionFailureMustNotDialUnprotected(t *testing.T) {
	previousPlatform, previousBG := intfBox, isBgProcess
	defer func() { intfBox, isBgProcess = previousPlatform, previousBG }()
	dir, err := os.MkdirTemp("", "egox-protect-ui-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	t.Chdir(dir)
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	isBgProcess = false
	intfBox = protectionTestPlatform{} // No active VPN: no IPC service is required.
	platform := &boxPlatformInterfaceWrapper{}
	if err := platform.AutoDetectInterfaceControl(fd); err != nil {
		t.Fatal(err)
	}
	intfBox = protectionTestPlatform{err: errors.New("VPN active; protection needs service")}
	if err := platform.AutoDetectInterfaceControl(fd); err == nil {
		t.Fatal("missing protection service allowed an unprotected dial")
	}
	server, err := serveProtect("protect_path", func(int) error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if err := platform.AutoDetectInterfaceControl(fd); err != nil {
		t.Fatal(err)
	}
}
