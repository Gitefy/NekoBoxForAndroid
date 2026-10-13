//go:build unix

package libcore

import (
	"runtime"
	"sync"
	"testing"
	"time"
)

func TestMainInstanceLifecycleRace(t *testing.T) {
	t.Chdir(t.TempDir()) // protect_path is isolated; never touch the app's socket.
	defer func() { mainInstance.Store(nil); _ = goServeProtect(false) }()
	var wg sync.WaitGroup
	done := make(chan struct{})
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-done:
				return
			default:
			}
			resetConnMu.Lock()
			resetConnLast = time.Time{} // Exercise each reset instead of the three-second debounce.
			resetConnMu.Unlock()
			ResetAllConnections(true)
			if _, err := urlTestDetour(nil); err != nil {
				t.Error(err)
			}
			runtime.Gosched()
		}
	}()
	for i := 0; i < 100; i++ {
		b := &BoxInstance{}
		if err := b.SetAsMain(); err != nil {
			t.Fatal(err)
		}
		runtime.Gosched()
		if err := b.Close(); err != nil {
			t.Fatal(err)
		}
	}
	close(done)
	wg.Wait()
}

func TestClosedInstanceCannotBecomeMain(t *testing.T) {
	b := &BoxInstance{}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	if err := b.SetAsMain(); err == nil {
		t.Fatal("closed instance published")
	}
}
