//go:build unix

package libcore

import (
	"fmt"
	"io"
	"net"
	"os"
	"time"

	"golang.org/x/sys/unix"
)

func serveProtect(path string, protect func(int) error) (io.Closer, error) {
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0600); err != nil {
		listener.Close()
		return nil, err
	}
	go func() {
		for {
			conn, err := listener.AcceptUnix()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(time.Second))
				reply := byte(0)
				if err := receiveProtectedFD(conn, protect); err == nil {
					reply = 1
				}
				_, _ = conn.Write([]byte{reply})
			}()
		}
	}()
	return listener, nil
}

func receiveProtectedFD(conn *net.UnixConn, protect func(int) error) error {
	data := make([]byte, 1)
	control := make([]byte, unix.CmsgSpace(4))
	// ReadMsgUnix waits through Go's network poller when the sender is not ready.
	// A raw recvmsg on this nonblocking descriptor would fail with EAGAIN.
	n, oobn, flags, _, err := conn.ReadMsgUnix(data, control)
	if err != nil {
		return err
	}
	messages, err := unix.ParseSocketControlMessage(control[:oobn])
	if err != nil {
		return err
	}
	var fds []int
	for _, message := range messages {
		rights, err := unix.ParseUnixRights(&message)
		if err != nil {
			for _, fd := range fds {
				_ = unix.Close(fd)
			}
			return err
		}
		fds = append(fds, rights...)
	}
	defer func() {
		for _, fd := range fds {
			_ = unix.Close(fd)
		}
	}()
	if n != 1 || flags&unix.MSG_CTRUNC != 0 || len(fds) != 1 {
		return fmt.Errorf("invalid protection request: bytes=%d fds=%d flags=%d", n, len(fds), flags)
	}
	return protect(fds[0])
}
