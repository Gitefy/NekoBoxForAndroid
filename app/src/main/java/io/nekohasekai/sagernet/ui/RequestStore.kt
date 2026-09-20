package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
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

    @Volatile
    private var acceptedGeneration: Long? = null

    private val listeners = mutableListOf<() -> Unit>()

    const val FILTER_ALL = "all"
    const val FILTER_PROXY = RequestFlowMapper.KIND_PROXY
    const val FILTER_DIRECT = RequestFlowMapper.KIND_DIRECT
    const val FILTER_BLOCK = RequestFlowMapper.KIND_BLOCK

    fun applyBatch(batch: RequestFlowBatch) {
        val previous = acceptedGeneration
        if (previous != null && batch.runtimeGeneration < previous) return
        acceptedGeneration = batch.runtimeGeneration
        replace(batch.items)
    }

    fun resetGenerationFence() {
        acceptedGeneration = null
    }

    fun replace(next: List<RequestFlowData>) {
        val copy = next.toList()
        if (copy == items) return
        items = copy
        notifyListeners()
    }

    fun clear() {
        items = emptyList()
        notifyListeners()
    }

    fun filtered(): List<RequestFlowData> {
        val kind = kindFilter
        val rawQuery = query.trim()
        if (kind == FILTER_ALL && rawQuery.isEmpty()) return items
        val needle = if (rawQuery.isEmpty()) "" else rawQuery.lowercase()
        return items.filter { flow ->
            if (kind != FILTER_ALL && flow.kind != kind) return@filter false
            if (needle.isEmpty()) return@filter true
            matchesQuery(flow, needle)
        }
    }

    internal fun matchesQuery(flow: RequestFlowData, needleLower: String): Boolean {
        return containsLower(flow.domain, needleLower) ||
            containsLower(flow.destinationAddress, needleLower) ||
            containsLower(flow.packageName, needleLower) ||
            containsLower(flow.routerName, needleLower) ||
            containsLower(flow.routerStableTag, needleLower) ||
            containsLower(flow.finalProfileName, needleLower) ||
            containsLower(flow.finalOutboundTag, needleLower) ||
            containsLower(flow.logicalOutbound, needleLower) ||
            containsLower(flow.matchedRuleText, needleLower)
    }

    private fun containsLower(value: String, needleLower: String): Boolean {
        if (value.isEmpty()) return false
        return value.lowercase().contains(needleLower)
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
