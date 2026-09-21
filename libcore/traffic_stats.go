package libcore

import (
	"context"
	"encoding/binary"
	"sort"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/experimental/v2rayapi"
)

const trafficStatsSnapshotVersion byte = 1

type trafficStatsRegistry struct {
	mu         sync.RWMutex
	tagToIndex map[string]uint16
	indexToTag []string
}

func newTrafficStatsRegistry(outbounds string) *trafficStatsRegistry {
	lines := strings.Split(outbounds, "\n")
	reg := &trafficStatsRegistry{
		tagToIndex: make(map[string]uint16, len(lines)),
		indexToTag: make([]string, 0, len(lines)),
	}
	for _, raw := range lines {
		tag := strings.TrimSpace(raw)
		if tag == "" {
			continue
		}
		if _, exists := reg.tagToIndex[tag]; !exists {
			idx := uint16(len(reg.indexToTag))
			reg.tagToIndex[tag] = idx
			reg.indexToTag = append(reg.indexToTag, tag)
		}
	}
	return reg
}

func (r *trafficStatsRegistry) lookup(tag string) (uint16, bool) {
	if r == nil {
		return 0, false
	}
	r.mu.RLock()
	idx, ok := r.tagToIndex[tag]
	r.mu.RUnlock()
	return idx, ok
}

func parseOutboundStatName(name string) (tag, direct string, ok bool) {
	const prefix = "outbound>>>"
	const trafficPart = ">>>traffic>>>"
	if !strings.HasPrefix(name, prefix) {
		return "", "", false
	}
	rem := name[len(prefix):]
	idx := strings.LastIndex(rem, trafficPart)
	if idx < 0 {
		return "", "", false
	}
	tag = rem[:idx]
	direct = rem[idx+len(trafficPart):]
	if direct != "uplink" && direct != "downlink" {
		return "", "", false
	}
	return tag, direct, true
}

type tagDelta struct {
	rx int64
	tx int64
}

type indexedDeltaEntry struct {
	index uint16
	rx    int64
	tx    int64
}

type namedDeltaEntry struct {
	tag string
	rx  int64
	tx  int64
}

func encodeTrafficStatsSnapshot(stats []*v2rayapi.Stat, reg *trafficStatsRegistry) []byte {
	deltas := make(map[string]*tagDelta)
	for _, stat := range stats {
		if stat == nil || stat.Value == 0 {
			continue
		}
		tag, direct, ok := parseOutboundStatName(stat.Name)
		if !ok {
			continue
		}
		d := deltas[tag]
		if d == nil {
			d = &tagDelta{}
			deltas[tag] = d
		}
		if direct == "downlink" {
			d.rx += stat.Value
		} else {
			d.tx += stat.Value
		}
	}

	var indexedEntries []indexedDeltaEntry
	var namedEntries []namedDeltaEntry

	for tag, d := range deltas {
		if d.rx == 0 && d.tx == 0 {
			continue
		}
		if idx, found := reg.lookup(tag); found {
			indexedEntries = append(indexedEntries, indexedDeltaEntry{
				index: idx,
				rx:    d.rx,
				tx:    d.tx,
			})
		} else {
			namedEntries = append(namedEntries, namedDeltaEntry{
				tag: tag,
				rx:  d.rx,
				tx:  d.tx,
			})
		}
	}

	sort.Slice(indexedEntries, func(i, j int) bool {
		return indexedEntries[i].index < indexedEntries[j].index
	})
	sort.Slice(namedEntries, func(i, j int) bool {
		return namedEntries[i].tag < namedEntries[j].tag
	})

	totalSize := 1 + 2 + len(indexedEntries)*(2+8+8) + 2
	for _, ne := range namedEntries {
		totalSize += 2 + len(ne.tag) + 8 + 8
	}

	buf := make([]byte, totalSize)
	buf[0] = trafficStatsSnapshotVersion
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(indexedEntries)))
	offset := 3

	for _, ie := range indexedEntries {
		binary.BigEndian.PutUint16(buf[offset:offset+2], ie.index)
		binary.BigEndian.PutUint64(buf[offset+2:offset+10], uint64(ie.rx))
		binary.BigEndian.PutUint64(buf[offset+10:offset+18], uint64(ie.tx))
		offset += 18
	}

	binary.BigEndian.PutUint16(buf[offset:offset+2], uint16(len(namedEntries)))
	offset += 2

	for _, ne := range namedEntries {
		tagBytes := []byte(ne.tag)
		binary.BigEndian.PutUint16(buf[offset:offset+2], uint16(len(tagBytes)))
		offset += 2
		copy(buf[offset:offset+len(tagBytes)], tagBytes)
		offset += len(tagBytes)
		binary.BigEndian.PutUint64(buf[offset:offset+8], uint64(ne.rx))
		binary.BigEndian.PutUint64(buf[offset+8:offset+16], uint64(ne.tx))
		offset += 16
	}

	return buf
}

func (b *BoxInstance) TrafficStatsSnapshot() []byte {
	if b == nil {
		return nil
	}
	b.access.Lock()
	v2api := b.v2api
	reg := b.trafficRegistry
	b.access.Unlock()

	if v2api == nil {
		return nil
	}

	resp, err := v2api.QueryStats(context.Background(), &v2rayapi.QueryStatsRequest{
		Reset_:   true,
		Patterns: []string{"outbound>>>"},
	})
	if err != nil || resp == nil {
		return nil
	}

	return encodeTrafficStatsSnapshot(resp.Stat, reg)
}
