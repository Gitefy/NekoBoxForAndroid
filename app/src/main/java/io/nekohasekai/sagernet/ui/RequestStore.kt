package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.aidl.RequestFlowData
import io.nekohasekai.sagernet.bg.proto.RequestFlowMapper

object RequestStore {
    @Volatile
    var items: List<RequestFlowData> = emptyList()
        private set

    @Volatile
    var query: String = ""

    @Volatile
    var kindFilter: String = FILTER_ALL

    private val listeners = mutableListOf<() -> Unit>()

    const val FILTER_ALL = "all"
    const val FILTER_PROXY = RequestFlowMapper.KIND_PROXY
    const val FILTER_DIRECT = RequestFlowMapper.KIND_DIRECT
    const val FILTER_BLOCK = RequestFlowMapper.KIND_BLOCK

    fun replace(next: List<RequestFlowData>) {
        items = next
        notifyListeners()
    }

    fun clear() {
        items = emptyList()
        notifyListeners()
    }

    fun filtered(): List<RequestFlowData> {
        val q = query.trim().lowercase()
        return items.filter { flow ->
            val kindOk = kindFilter == FILTER_ALL || flow.kind == kindFilter
            if (!kindOk) return@filter false
            if (q.isEmpty()) return@filter true
            listOf(
                flow.domain,
                flow.destinationAddress,
                flow.packageName,
                flow.routerName,
                flow.routerStableTag,
                flow.finalProfileName,
                flow.finalOutboundTag,
                flow.logicalOutbound,
                flow.matchedRuleText,
            ).any { it.lowercase().contains(q) }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
