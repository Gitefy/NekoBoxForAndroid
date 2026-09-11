package io.nekohasekai.sagernet.ui

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
        assertEquals(0, RequestStore.filtered().size)
    }
}
