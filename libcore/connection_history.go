package libcore

import (
	"encoding/json"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/sagernet/sing-box/common/trafficcontrol"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/observable"
)

const (
	connectionHistoryMax       = 300
	connectionHistoryMaxAge    = 10 * time.Minute
	boundRuleText              = 256
	boundShortText             = 128
	boundTagText               = 128
	boundAddressText           = 64
	boundIDText                = 64
)

type connectionFlow struct {
	ID                   string `json:"id"`
	CreatedAt            int64  `json:"createdAt"`
	ClosedAt             int64  `json:"closedAt"`
	Closed               bool   `json:"closed"`
	Network              string `json:"network"`
	UID                  int32  `json:"uid"`
	PackageNames         string `json:"packageNames"`
	Domain               string `json:"domain"`
	DestinationAddress   string `json:"destinationAddress"`
	DestinationPort      int    `json:"destinationPort"`
	OriginDestination    string `json:"originDestination"`
	MatchedRuleText      string `json:"matchedRuleText"`
	Chain                string `json:"chain"`
	LogicalOutbound      string `json:"logicalOutbound"`
	FinalOutboundTag     string `json:"finalOutboundTag"`
	UploadBytes          int64  `json:"uploadBytes"`
	DownloadBytes        int64  `json:"downloadBytes"`
}

type connectionSnapshotEnvelope struct {
	Flows []connectionFlow `json:"flows"`
}

type connectionHistory struct {
	now    func() time.Time
	max    int
	maxAge time.Duration

	mu      sync.Mutex
	byID    map[string]*connectionFlow
	unsub   func()
	stopCh  chan struct{}
	stopped bool
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

func (h *connectionHistory) Upsert(flow connectionFlow) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.upsertLocked(flow)
	h.evictLocked()
}

func (h *connectionHistory) upsertLocked(flow connectionFlow) {
	if flow.ID == "" {
		return
	}
	if existing, ok := h.byID[flow.ID]; ok {
		*existing = flow
		return
	}
	copied := flow
	h.byID[flow.ID] = &copied
}

func (h *connectionHistory) evictLocked() {
	cutoff := h.now().Add(-h.maxAge).UnixMilli()
	for id, flow := range h.byID {
		created := flow.CreatedAt
		if created > 0 && created < cutoff {
			delete(h.byID, id)
		}
	}
	if len(h.byID) <= h.max {
		return
	}
	all := make([]*connectionFlow, 0, len(h.byID))
	for _, flow := range h.byID {
		all = append(all, flow)
	}
	sort.Slice(all, func(i, j int) bool {
		return all[i].CreatedAt > all[j].CreatedAt
	})
	for _, extra := range all[h.max:] {
		delete(h.byID, extra.ID)
	}
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
		if out[i].CreatedAt == out[j].CreatedAt {
			return out[i].ID > out[j].ID
		}
		return out[i].CreatedAt > out[j].CreatedAt
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
	for _, meta := range manager.Connections() {
		h.Upsert(flowFromTracker(meta))
	}
	for _, meta := range manager.ClosedConnections() {
		h.Upsert(flowFromTracker(meta))
	}
	sub, done, err := manager.SubscribeEvents()
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
	go h.listen(sub, done, stopCh, manager)
}

func (h *connectionHistory) listen(
	sub observable.Subscription[trafficcontrol.ConnectionEvent],
	done <-chan struct{},
	stopCh <-chan struct{},
	manager *trafficcontrol.Manager,
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
				h.Upsert(flowFromTracker(event.Metadata))
			}
			if manager != nil {
				for _, meta := range manager.Connections() {
					h.Upsert(flowFromTracker(meta))
				}
			}
		}
	}
}

func (h *connectionHistory) MergeLive(manager *trafficcontrol.Manager) {
	if manager == nil {
		return
	}
	for _, meta := range manager.Connections() {
		h.Upsert(flowFromTracker(meta))
	}
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
