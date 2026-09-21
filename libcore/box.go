package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/experimental/v2rayapi"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"libcore/urltest"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance

var buildRevision = "unknown"

func VersionBox() string {
	return formatVersionBox(constant.Version, buildRevision)
}

func formatVersionBox(coreVersion, revision string) string {
	version := []string{
		"sing-box: " + coreVersion,
		"revision: " + revision,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

var (
	resetConnMu   sync.Mutex
	resetConnLast time.Time
)

func ResetAllConnections(system bool) {
	if !system {
		log.Println("TODO: Reset user connections")
		return
	}
	resetConnMu.Lock()
	now := time.Now()
	if !resetConnLast.IsZero() && now.Sub(resetConnLast) < 3*time.Second {
		resetConnMu.Unlock()
		log.Println("Reset system connections: throttled")
		return
	}
	resetConnLast = now
	resetConnMu.Unlock()
	if mainInstance != nil && mainInstance.connectionManager != nil {
		mainInstance.connectionManager.CloseAll()
		log.Println("Reset system connections done")
	} else {
		log.Println("Reset system connections: no running instance")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api             *v2rayapi.StatsService
	trafficRegistry   *trafficStatsRegistry
	connectionManager adapter.ConnectionManager
	selector          outboundSelector
	pauseManager      pause.Manager
	trafficManager    *trafficcontrol.Manager
	connHistory       *connectionHistory
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// sing-box 1.15 removed the nekoutils hooks that resolved "geosite:" /
	// "geoip:" local rule-set references from the consolidated geo databases;
	// materialize them as binary rule-set files before the router loads them.
	err = extractLocalGeoRuleSets(options)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("extract geo rule-sets: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:               instance,
		cancel:            cancel,
		pauseManager:      service.FromContext[pause.Manager](ctx),
		connectionManager: service.FromContext[adapter.ConnectionManager](ctx),
		trafficManager:    service.PtrFromContext[trafficcontrol.Manager](ctx),
		connHistory:       newConnectionHistory(nil),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		b.selector = asSelector(proxy)
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		err := b.Box.Start()
		if err != nil {
			return err
		}
		if b.connHistory != nil {
			b.connHistory.Attach(b.trafficManager)
		}
		return nil
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	if b.connHistory != nil {
		b.connHistory.Dispose()
		b.connHistory = nil
	}

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

// ConnectionSnapshotRevision is a legacy fallback only. Prefer ConnectionSnapshotSince.
func (b *BoxInstance) ConnectionSnapshotRevision() int64 {
	if b == nil {
		return 0
	}
	b.access.Lock()
	if b.state != 1 {
		b.access.Unlock()
		return 0
	}
	history := b.connHistory
	manager := b.trafficManager
	b.access.Unlock()
	if history == nil {
		return 0
	}
	return history.MergeLive(manager)
}

// ConnectionSnapshot is a legacy fallback only. Prefer ConnectionSnapshotSince.
func (b *BoxInstance) ConnectionSnapshot() *StringBox {
	if b == nil {
		return wrapString(`{"flows":[]}`)
	}
	b.access.Lock()
	if b.state != 1 {
		b.access.Unlock()
		return wrapString(`{"flows":[]}`)
	}
	history := b.connHistory
	manager := b.trafficManager
	b.access.Unlock()
	if history == nil {
		return wrapString(`{"flows":[]}`)
	}
	history.MergeLive(manager)
	return wrapString(history.SnapshotJSON())
}

func (b *BoxInstance) ConnectionSnapshotSince(lastRevision int64) *ConnectionSnapshotResponse {
	if b == nil {
		return &ConnectionSnapshotResponse{Revision: 0, Unchanged: true, Payload: wrapString(`{"flows":[]}`)}
	}
	b.access.Lock()
	if b.state != 1 {
		b.access.Unlock()
		return &ConnectionSnapshotResponse{Revision: 0, Unchanged: true, Payload: wrapString(`{"flows":[]}`)}
	}
	history := b.connHistory
	manager := b.trafficManager
	b.access.Unlock()
	if history == nil {
		return &ConnectionSnapshotResponse{Revision: 0, Unchanged: true, Payload: wrapString(`{"flows":[]}`)}
	}
	return history.MergeAndSnapshotSince(manager, lastRevision)
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	// boxapi was removed in sing-box 1.15: the V2Ray stats service now lives in
	// experimental/v2rayapi and implements adapter.ConnectionTracker directly.
	statsService := v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	if statsService == nil {
		return
	}
	b.v2api = statsService
	b.trafficRegistry = newTrafficStatsRegistry(outbounds)
	b.Box.Router().AppendTracker(statsService)
}

// QueryStats is a legacy fallback only. Prefer TrafficStatsSnapshot.
func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	// The Android traffic looper accumulates per-interval deltas, so counters
	// are reset on read (same behavior as the removed boxapi helper).
	response, err := b.v2api.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name:   fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct),
		Reset_: true,
	})
	if err != nil || response == nil || response.Stat == nil {
		return 0
	}
	return response.Stat.Value
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return false
	}
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func (b *BoxInstance) SelectOutboundFor(selectorTag, tag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return false
	}
	proxy, ok := b.Outbound().Outbound(selectorTag)
	if !ok {
		return false
	}
	return selectOutboundFor(proxy, tag)
}

// CurrentOutboundFor is a legacy fallback only. Prefer CurrentGroupSelections.
func (b *BoxInstance) CurrentOutboundFor(groupTag string) string {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return ""
	}
	proxy, ok := b.Outbound().Outbound(groupTag)
	if !ok {
		return ""
	}
	return queryGroupSelection(proxy)
}

func (b *BoxInstance) RefreshURLTestFor(groupTag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return false
	}
	proxy, ok := b.Outbound().Outbound(groupTag)
	if !ok {
		return false
	}
	return refreshURLTestFor(proxy)
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	return urlTest(i, link, timeout, "")
}

// UrlTestWithTarget measures link through the outbound or endpoint identified by targetTag.
// An empty targetTag uses the configuration's declared default outbound.
func UrlTestWithTarget(i *BoxInstance, link string, timeout int32, targetTag string) (latency int32, err error) {
	return urlTest(i, link, timeout, targetTag)
}

func urlTest(i *BoxInstance, link string, timeout int32, targetTag string) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })

	ctx := context.Background()
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeout)*time.Millisecond)
		defer cancel()
	}

	detour, err := urlTestDetourWithTarget(i, targetTag)
	if err != nil {
		return 0, err
	}

	latency, _, err = urltest.Probe(ctx, detour, link)
	return latency, err
}

// urlTestDetour resolves the dialer a URL test has to go through:
//
//   - a test instance: that instance's default outbound, i.e. the node under test;
//   - no instance: the default outbound of the running main instance;
//   - no instance at all: the system network (direct).
func urlTestDetour(i *BoxInstance) (N.Dialer, error) {
	return urlTestDetourWithTarget(i, "")
}

// urlTestDetourWithTarget resolves a caller-specified outbound or endpoint.
// sing-box's outbound manager shares this namespace with endpoints.
func urlTestDetourWithTarget(i *BoxInstance, targetTag string) (N.Dialer, error) {
	var boxAccessor urltest.OutboundAccessor
	if i != nil && i.Box != nil {
		boxAccessor = i.Box
	} else if mainInstance != nil && mainInstance.Box != nil {
		boxAccessor = mainInstance.Box
	}
	return urltest.ResolveTargetDialer(boxAccessor, targetTag)
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
