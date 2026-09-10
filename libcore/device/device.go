package device

import (
	"runtime"
)

func NumUDPWorkers() int {
	n := runtime.GOMAXPROCS(0)
	if n < 2 {
		return 2
	}
	if n > 4 {
		return 4
	}
	return n
}

func TunWorkerCount() int {
	return NumUDPWorkers()
}
