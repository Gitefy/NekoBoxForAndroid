package libcore

import (
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
)

type outboundSelector interface {
	SelectOutbound(tag string) bool
}

func asSelector(outbound adapter.Outbound) outboundSelector {
	if outbound == nil {
		return nil
	}
	if selector, ok := outbound.(*group.Selector); ok {
		return selector
	}
	return nil
}

func selectOutboundFor(outbound adapter.Outbound, tag string) bool {
	selector := asSelector(outbound)
	if selector == nil {
		return false
	}
	return selector.SelectOutbound(tag)
}

func refreshURLTestFor(outbound adapter.Outbound) bool {
	if outbound == nil {
		return false
	}
	urlTest, ok := outbound.(*group.URLTest)
	if !ok {
		return false
	}
	go urlTest.CheckOutbounds()
	return true
}

func queryGroupSelection(outbound adapter.Outbound) string {
	if outbound == nil {
		return ""
	}
	switch g := outbound.(type) {
	case *group.Selector:
		return g.Now()
	case *group.URLTest:
		return g.Now()
	default:
		return ""
	}
}

func (b *BoxInstance) CurrentGroupSelections(groupTags string) *StringBox {
	if b == nil {
		return wrapString("")
	}
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.Box == nil {
		return wrapString("")
	}
	outbounds := b.Outbound()
	if outbounds == nil {
		return wrapString("")
	}

	tags := strings.Split(groupTags, "\n")
	var sb strings.Builder
	for _, rawTag := range tags {
		tag := strings.TrimSpace(rawTag)
		if tag == "" {
			continue
		}
		proxy, ok := outbounds.Outbound(tag)
		if !ok {
			continue
		}
		selected := queryGroupSelection(proxy)
		if selected != "" {
			sb.WriteString(tag)
			sb.WriteByte('\t')
			sb.WriteString(selected)
			sb.WriteByte('\n')
		}
	}
	return wrapString(sb.String())
}
