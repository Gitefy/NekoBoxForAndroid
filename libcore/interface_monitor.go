package libcore

import (
	"net"
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

var (
	defaultInterfaceLock     sync.RWMutex
	defaultPlatformInterface *control.Interface
	interfaceCallbacks       list.List[tun.DefaultInterfaceUpdateCallback]
)

type interfaceMonitorStub struct{}

func (s *interfaceMonitorStub) Start() error {
	return nil
}

func (s *interfaceMonitorStub) Close() error {
	return nil
}

func (s *interfaceMonitorStub) DefaultInterface() *control.Interface {
	defaultInterfaceLock.RLock()
	defer defaultInterfaceLock.RUnlock()
	return defaultPlatformInterface
}

func (s *interfaceMonitorStub) OverrideAndroidVPN() bool {
	return true
}

func (s *interfaceMonitorStub) AndroidVPNEnabled() bool {
	return true
}

func (s *interfaceMonitorStub) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	defaultInterfaceLock.Lock()
	defer defaultInterfaceLock.Unlock()
	return interfaceCallbacks.PushBack(callback)
}

func (s *interfaceMonitorStub) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	if element == nil {
		return
	}
	defaultInterfaceLock.Lock()
	defer defaultInterfaceLock.Unlock()
	interfaceCallbacks.Remove(element)
}

func (s *interfaceMonitorStub) RegisterMyInterface(interfaceName string) {
}

func (s *interfaceMonitorStub) MyInterface() string {
	return ""
}

func (s *interfaceMonitorStub) MyInterfaces() []string {
	return nil
}

func UpdateDefaultInterface(interfaceName string, interfaceIndex int, resetConnections bool) {
	defaultInterfaceLock.Lock()
	previous := defaultPlatformInterface
	if interfaceName == "" || interfaceIndex <= 0 {
		if previous == nil {
			defaultInterfaceLock.Unlock()
			return
		}
		defaultPlatformInterface = nil
	} else {
		if previous != nil && previous.Name == interfaceName && previous.Index == interfaceIndex {
			defaultInterfaceLock.Unlock()
			return
		}
		defaultPlatformInterface = &control.Interface{
			Index: interfaceIndex,
			Name:  interfaceName,
			Flags: net.FlagUp,
		}
	}
	current := defaultPlatformInterface
	callbacks := interfaceCallbacks.Array()
	defaultInterfaceLock.Unlock()

	// Offline/online transitions must pause/wake the core. An online path switch
	// may preserve connections when the user disabled network-change resets.
	if previous != nil && current != nil && !resetConnections {
		return
	}
	for _, callback := range callbacks {
		callback(current, 0)
	}
}
