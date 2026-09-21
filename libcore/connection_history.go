package libcore

import (
	"encoding/json"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/sagernet/sing-box/common/trafficcontrol"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/observable"
)

const (
	connectionHistoryMax    = 300
	connectionHistoryMaxAge = 10 * time.Minute
	boundRuleText           = 256
	boundShortText          = 128
	boundTagText            = 128
	boundAddressText        = 64
	boundIDText             = 64
)

type connectionFlow struct {
	ID                 string `json:"id"`
	CreatedAt          int64  `json:"createdAt"`
	ClosedAt           int64  `json:"closedAt"`
	Closed             bool   `json:"closed"`
	Network            string `json:"network"`
	UID                int32  `json:"uid"`
	PackageNames       string `json:"packageNames"`
	Domain             string `json:"domain"`
	DestinationAddress string `json:"destinationAddress"`
	DestinationPort    int    `json:"destinationPort"`
	OriginDestination  string `json:"originDestination"`
	MatchedRuleText    string `json:"matchedRuleText"`
	Chain              string `json:"chain"`
	LogicalOutbound    string `json:"logicalOutbound"`
	FinalOutboundTag   string `json:"finalOutboundTag"`
	UploadBytes        int64  `json:"uploadBytes"`
	DownloadBytes      int64  `json:"downloadBytes"`
}

type connectionSnapshotEnvelope struct {
	Flows []connectionFlow `json:"flows"`
}

type ConnectionSnapshotResponse struct {
	Revision  int64
	Unchanged bool
	Payload   *StringBox
}

type liveConnectionSource interface {
	Connections() []*trafficcontrol.TrackerMetadata
	ClosedConnections() []*trafficcontrol.TrackerMetadata
}

type connectionHistory struct {
	now    func() time.Time
	max    int
	maxAge time.Duration

	mu       sync.Mutex
	byID     map[string]*connectionFlow
	unsub    func()
	stopCh   chan struct{}
	stopped  bool
	revision int64

	cachedRevision int64
	cachedJSON     string

	liveScans       atomic.Int64
	trackerConverts atomic.Int64
	evictPasses     atomic.Int64
}

func newConnectionHistory(now func() time.Time) *connectionHistory {
	if now == nil {
		now = time.Now
	}
	return &connectionHistory{
		now:    now,
		max:    connectionHistoryMax,
		maxAge: connectionHistoryMaxAge,
		byID:   make(map[string]*connectionFlow),
		stopCh: make(chan struct{}),
	}
}

func rawIP(addr M.Socksaddr) string {
	if addr.Addr.IsValid() {
		return addr.Addr.String()
	}
	return ""
}

func destinationIPAndPort(dest, origin M.Socksaddr) (string, int) {
	ip := rawIP(dest)
	if ip == "" {
		ip = rawIP(origin)
	}
	port := int(dest.Port)
	if port == 0 {
		port = int(origin.Port)
	}
	return ip, port
}

func boundString(value string, limit int) string {
	if limit <= 0 || value == "" {
		return value
	}
	if utf8.RuneCountInString(value) <= limit {
		return value
	}
	runes := []rune(value)
	return string(runes[:limit])
}

func flowFromTracker(meta *trafficcontrol.TrackerMetadata) connectionFlow {
	if meta == nil {
		return connectionFlow{}
	}
	id := boundString(meta.ID.String(), boundIDText)
	network := boundString(strings.ToLower(meta.Metadata.Network), 8)
	domain := meta.Metadata.Domain
	if domain == "" {
		domain = meta.Metadata.Destination.Fqdn
	}
	var uid int32
	var packages string
	if meta.Metadata.ProcessInfo != nil {
		uid = meta.Metadata.ProcessInfo.UserId
		packages = boundString(strings.Join(meta.Metadata.ProcessInfo.PackageNames, ","), boundShortText)
	}
	ruleText := "final"
	if meta.Rule != nil {
		ruleText = meta.Rule.String()
		if action := meta.Rule.Action(); action != nil {
			ruleText = ruleText + " => " + action.String()
		}
	}
	logical := ""
	if len(meta.Chain) > 0 {
		logical = meta.Chain[len(meta.Chain)-1]
	}
	if logical == "" {
		logical = meta.Metadata.Outbound
	}
	destIP, destPort := destinationIPAndPort(meta.Metadata.Destination, meta.Metadata.OriginDestination)
	origin := ""
	if originIP := rawIP(meta.Metadata.OriginDestination); originIP != "" {
		origin = boundString(originIP, boundAddressText)
	} else if meta.Metadata.OriginDestination.IsValid() {
		origin = boundString(meta.Metadata.OriginDestination.AddrString(), boundAddressText)
	}
	closedAt := int64(0)
	if !meta.ClosedAt.IsZero() {
		closedAt = meta.ClosedAt.UnixMilli()
	}
	upload := int64(0)
	download := int64(0)
	if meta.Upload != nil {
		upload = meta.Upload.Load()
	}
	if meta.Download != nil {
		download = meta.Download.Load()
	}
	return connectionFlow{
		ID:                 id,
		CreatedAt:          meta.CreatedAt.UnixMilli(),
		ClosedAt:           closedAt,
		Closed:             closedAt > 0,
		Network:            network,
		UID:                uid,
		PackageNames:       packages,
		Domain:             boundString(domain, boundShortText),
		DestinationAddress: boundString(destIP, boundAddressText),
		DestinationPort:    destPort,
		OriginDestination:  origin,
		MatchedRuleText:    boundString(ruleText, boundRuleText),
		Chain:              boundString(strings.Join(meta.Chain, ","), boundRuleText),
		LogicalOutbound:    boundString(logical, boundTagText),
		FinalOutboundTag:   boundString(meta.Outbound, boundTagText),
		UploadBytes:        upload,
		DownloadBytes:      download,
	}
}

func preferText(keep, incoming string, locked bool) string {
	if locked {
		if keep != "" {
			return keep
		}
		return incoming
	}
	if incoming != "" {
		return incoming
	}
	return keep
}

func mergeConnectionFlow(dst *connectionFlow, src connectionFlow) bool {
	if dst.ID == "" {
		*dst = src
		return true
	}
	old := *dst
	closed := dst.Closed || src.Closed
	closedAt := dst.ClosedAt
	if src.Closed && src.ClosedAt > closedAt {
		closedAt = src.ClosedAt
	}
	created := dst.CreatedAt
	if created == 0 || (src.CreatedAt > 0 && src.CreatedAt < created) {
		created = src.CreatedAt
	}
	upload := dst.UploadBytes
	if src.UploadBytes > upload {
		upload = src.UploadBytes
	}
	download := dst.DownloadBytes
	if src.DownloadBytes > download {
		download = src.DownloadBytes
	}
	identityLocked := dst.Closed && !src.Closed
	dst.CreatedAt = created
	dst.Network = preferText(dst.Network, src.Network, identityLocked)
	if !identityLocked || dst.UID == 0 {
		if src.UID != 0 {
			dst.UID = src.UID
		}
	}
	dst.PackageNames = preferText(dst.PackageNames, src.PackageNames, identityLocked)
	dst.Domain = preferText(dst.Domain, src.Domain, identityLocked)
	dst.DestinationAddress = preferText(dst.DestinationAddress, src.DestinationAddress, identityLocked)
	if !identityLocked || dst.DestinationPort == 0 {
		if src.DestinationPort != 0 {
			dst.DestinationPort = src.DestinationPort
		}
	}
	dst.OriginDestination = preferText(dst.OriginDestination, src.OriginDestination, identityLocked)
	dst.MatchedRuleText = preferText(dst.MatchedRuleText, src.MatchedRuleText, identityLocked)
	dst.Chain = preferText(dst.Chain, src.Chain, identityLocked)
	dst.LogicalOutbound = preferText(dst.LogicalOutbound, src.LogicalOutbound, identityLocked)
	dst.FinalOutboundTag = preferText(dst.FinalOutboundTag, src.FinalOutboundTag, identityLocked)
	dst.UploadBytes = upload
	dst.DownloadBytes = download
	dst.Closed = closed
	dst.ClosedAt = closedAt

	return *dst != old
}

func (h *connectionHistory) Upsert(flow connectionFlow) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.stopped {
		return
	}
	if h.upsertLocked(flow) {
		h.evictLocked()
	}
}

func (h *connectionHistory) ApplyEvent(meta *trafficcontrol.TrackerMetadata) {
	if meta == nil {
		return
	}
	h.trackerConverts.Add(1)
	h.Upsert(flowFromTracker(meta))
}

func (h *connectionHistory) upsertLocked(flow connectionFlow) bool {
	if flow.ID == "" {
		return false
	}
	if existing, ok := h.byID[flow.ID]; ok {
		if mergeConnectionFlow(existing, flow) {
			h.revision++
		}
		return false
	}
	copied := flow
	h.byID[flow.ID] = &copied
	h.revision++
	return true
}

func flowLess(a, b connectionFlow) bool {
	if a.CreatedAt == b.CreatedAt {
		return a.ID > b.ID
	}
	return a.CreatedAt > b.CreatedAt
}

func (h *connectionHistory) evictLocked() bool {
	h.evictPasses.Add(1)
	changed := false
	cutoff := h.now().Add(-h.maxAge).UnixMilli()
	for id, flow := range h.byID {
		created := flow.CreatedAt
		if created > 0 && created < cutoff {
			delete(h.byID, id)
			changed = true
		}
	}
	if len(h.byID) <= h.max {
		if changed {
			h.revision++
		}
		return changed
	}
	all := make([]*connectionFlow, 0, len(h.byID))
	for _, flow := range h.byID {
		all = append(all, flow)
	}
	sort.Slice(all, func(i, j int) bool {
		return flowLess(*all[i], *all[j])
	})
	for _, extra := range all[h.max:] {
		delete(h.byID, extra.ID)
		changed = true
	}
	if changed {
		h.revision++
	}
	return changed
}

func (h *connectionHistory) mergeTrackers(metas []*trafficcontrol.TrackerMetadata) {
	if len(metas) == 0 {
		h.mu.Lock()
		defer h.mu.Unlock()
		if h.stopped {
			return
		}
		h.evictLocked()
		return
	}
	flows := make([]connectionFlow, 0, len(metas))
	for _, meta := range metas {
		h.trackerConverts.Add(1)
		flows = append(flows, flowFromTracker(meta))
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.stopped {
		return
	}
	for _, flow := range flows {
		h.upsertLocked(flow)
	}
	h.evictLocked()
}

func (h *connectionHistory) Snapshot() []connectionFlow {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.evictLocked()
	out := make([]connectionFlow, 0, len(h.byID))
	for _, flow := range h.byID {
		out = append(out, *flow)
	}
	sort.Slice(out, func(i, j int) bool {
		return flowLess(out[i], out[j])
	})
	return out
}

func (h *connectionHistory) SnapshotJSON() string {
	raw, err := json.Marshal(connectionSnapshotEnvelope{Flows: h.Snapshot()})
	if err != nil {
		return `{"flows":[]}`
	}
	return string(raw)
}

func (h *connectionHistory) Attach(manager *trafficcontrol.Manager) {
	if manager == nil {
		return
	}
	h.mu.Lock()
	stopped := h.stopped
	h.mu.Unlock()
	if stopped {
		return
	}
	sub, done, err := manager.SubscribeEvents()
	h.MergeLive(manager)
	h.mergeClosed(manager)
	if err != nil || sub == nil {
		return
	}
	h.mu.Lock()
	if h.stopped {
		h.mu.Unlock()
		manager.UnSubscribeEvents(sub)
		return
	}
	h.unsub = func() { manager.UnSubscribeEvents(sub) }
	stopCh := h.stopCh
	h.mu.Unlock()
	go h.listen(sub, done, stopCh)
}

func (h *connectionHistory) listen(
	sub observable.Subscription[trafficcontrol.ConnectionEvent],
	done <-chan struct{},
	stopCh <-chan struct{},
) {
	for {
		select {
		case <-stopCh:
			return
		case <-done:
			return
		case event, ok := <-sub:
			if !ok {
				return
			}
			if event.Metadata != nil {
				h.ApplyEvent(event.Metadata)
			}
		}
	}
}

func (h *connectionHistory) MergeAndSnapshotSince(source liveConnectionSource, lastRevision int64) *ConnectionSnapshotResponse {
	if source != nil {
		h.liveScans.Add(1)
		metas := source.Connections()
		var flows []connectionFlow
		if len(metas) > 0 {
			flows = make([]connectionFlow, 0, len(metas))
			for _, meta := range metas {
				h.trackerConverts.Add(1)
				flows = append(flows, flowFromTracker(meta))
			}
		}
		h.mu.Lock()
		if !h.stopped {
			for _, flow := range flows {
				h.upsertLocked(flow)
			}
			h.evictLocked()
		}
	} else {
		h.mu.Lock()
		if !h.stopped {
			h.evictLocked()
		}
	}

	rev := h.revision
	if lastRevision >= 0 && rev == lastRevision {
		h.mu.Unlock()
		return &ConnectionSnapshotResponse{
			Revision:  rev,
			Unchanged: true,
			Payload:   wrapString(""),
		}
	}

	if h.cachedRevision == rev && h.cachedJSON != "" {
		cached := h.cachedJSON
		h.mu.Unlock()
		return &ConnectionSnapshotResponse{
			Revision:  rev,
			Unchanged: false,
			Payload:   wrapString(cached),
		}
	}

	out := make([]connectionFlow, 0, len(h.byID))
	for _, flow := range h.byID {
		out = append(out, *flow)
	}
	sort.Slice(out, func(i, j int) bool {
		return flowLess(out[i], out[j])
	})
	h.mu.Unlock()

	raw, err := json.Marshal(connectionSnapshotEnvelope{Flows: out})
	jsonStr := string(raw)
	if err != nil {
		jsonStr = `{"flows":[]}`
	}

	h.mu.Lock()
	if h.revision == rev {
		h.cachedRevision = rev
		h.cachedJSON = jsonStr
	}
	h.mu.Unlock()

	return &ConnectionSnapshotResponse{
		Revision:  rev,
		Unchanged: false,
		Payload:   wrapString(jsonStr),
	}
}

func (h *connectionHistory) MergeLive(source liveConnectionSource) int64 {
	if source == nil {
		h.mu.Lock()
		defer h.mu.Unlock()
		if !h.stopped {
			h.evictLocked()
		}
		return h.revision
	}
	h.liveScans.Add(1)
	h.mergeTrackers(source.Connections())
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.revision
}

func (h *connectionHistory) Revision() int64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.revision
}

func (h *connectionHistory) mergeClosed(source liveConnectionSource) {
	if source == nil {
		return
	}
	h.mergeTrackers(source.ClosedConnections())
}

func (h *connectionHistory) Dispose() {
	h.mu.Lock()
	if h.stopped {
		h.mu.Unlock()
		return
	}
	h.stopped = true
	unsub := h.unsub
	h.unsub = nil
	close(h.stopCh)
	h.mu.Unlock()
	if unsub != nil {
		unsub()
	}
}
