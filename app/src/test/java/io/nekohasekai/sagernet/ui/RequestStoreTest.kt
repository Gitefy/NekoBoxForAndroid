package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.aidl.RequestFlowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RequestStoreTest {
    @Test
    fun searchAndFilterDoNotUseDatabase() {
        RequestStore.replace(
            listOf(
                RequestFlowData(id = "1", domain = "youtube.com", kind = "proxy", routerName = "US"),
                RequestFlowData(id = "2", domain = "qq.com", kind = "direct", finalProfileName = "DIRECT"),
            )
        )
        RequestStore.query = "youtube"
        RequestStore.kindFilter = RequestStore.FILTER_ALL
        assertEquals(listOf("1"), RequestStore.filtered().map { it.id })
        RequestStore.query = ""
        RequestStore.kindFilter = RequestStore.FILTER_DIRECT
        assertEquals(listOf("2"), RequestStore.filtered().map { it.id })
        RequestStore.clear()
        RequestStore.resetGenerationFence()
        assertEquals(0, RequestStore.filtered().size)
    }

    @Test
    fun staleRuntimeBatchCannotReplaceNewerBatch() {
        RequestStore.resetGenerationFence()
        RequestStore.applyBatch(
            RequestFlowBatch(arrayListOf(RequestFlowData(id = "new")), runtimeGeneration = 5L),
        )
        RequestStore.applyBatch(
            RequestFlowBatch(arrayListOf(RequestFlowData(id = "old")), runtimeGeneration = 4L),
        )
        assertEquals(listOf("new"), RequestStore.items.map { it.id })
        RequestStore.applyBatch(
            RequestFlowBatch(arrayListOf(RequestFlowData(id = "newer")), runtimeGeneration = 6L),
        )
        assertEquals(listOf("newer"), RequestStore.items.map { it.id })
        RequestStore.clear()
        RequestStore.resetGenerationFence()
    }

    @Test
    fun serviceReconnectResetsGenerationFence() {
        RequestStore.resetGenerationFence()
        RequestStore.applyBatch(
            RequestFlowBatch(arrayListOf(RequestFlowData(id = "old-runtime")), runtimeGeneration = 9L),
        )
        RequestStore.resetGenerationFence()
        RequestStore.applyBatch(
            RequestFlowBatch(arrayListOf(RequestFlowData(id = "new-runtime")), runtimeGeneration = 1L),
        )
        assertEquals(listOf("new-runtime"), RequestStore.items.map { it.id })
        RequestStore.clear()
        RequestStore.resetGenerationFence()
    }

    @Test
    fun emptyQueryAllKindReturnsTheSameListReference() {
        val rows = listOf(
            RequestFlowData(id = "1", domain = "a.com", kind = "proxy"),
            RequestFlowData(id = "2", domain = "b.com", kind = "direct"),
        )
        RequestStore.replace(rows)
        RequestStore.query = "   "
        RequestStore.kindFilter = RequestStore.FILTER_ALL
        assertSame(RequestStore.items, RequestStore.filtered())
        RequestStore.clear()
        RequestStore.resetGenerationFence()
    }

    @Test
    fun searchMatchesLegacyLowercaseContainsAcrossAllFields() {
        val flow = RequestFlowData(
            id = "1",
            domain = "YouTube.com",
            destinationAddress = "1.1.1.1",
            packageName = "com.google.android.youtube",
            routerName = "US low",
            routerStableTag = "router.us-low",
            finalProfileName = "US-01",
            finalOutboundTag = "proxy-us-01",
            logicalOutbound = "router.us-low",
            matchedRuleText = "geosite:youtube",
            kind = "proxy",
        )
        RequestStore.replace(listOf(flow))
        RequestStore.kindFilter = RequestStore.FILTER_ALL
        val queries = listOf(
            "youtube.com", "YOUTUBE", "1.1.1.1", "android.youtube",
            "us low", "us-low", "US-01", "proxy-us", "geosite", "no-such",
        )
        for (query in queries) {
            RequestStore.query = query
            val optimized = RequestStore.filtered().map { it.id }
            val legacy = listOf(flow).filter { legacyMatches(it, query) }.map { it.id }
            assertEquals(query, legacy, optimized)
        }
        RequestStore.clear()
        RequestStore.resetGenerationFence()
    }

    private fun legacyMatches(flow: RequestFlowData, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        val fields = listOf(
            flow.domain,
            flow.destinationAddress,
            flow.packageName,
            flow.routerName,
            flow.routerStableTag,
            flow.finalProfileName,
            flow.finalOutboundTag,
            flow.logicalOutbound,
            flow.matchedRuleText,
        )
        return fields.any { it.lowercase().contains(needle) }
    }
}
