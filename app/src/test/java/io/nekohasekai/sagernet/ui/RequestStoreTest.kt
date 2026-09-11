package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.aidl.RequestFlowData
import org.junit.Assert.assertEquals
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
}
