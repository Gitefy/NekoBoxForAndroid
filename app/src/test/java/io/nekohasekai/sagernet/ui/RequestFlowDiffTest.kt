package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.aidl.RequestFlowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestFlowDiffTest {
    @Test
    fun distinctConnectionsAreNotTheSameItem() {
        val a = RequestFlowData(id = "1", createdAt = 1L, uploadBytes = 1)
        val b = RequestFlowData(id = "2", createdAt = 1L, uploadBytes = 1)
        assertFalse(RequestFlowDiff.sameItem(a, b))
    }

    @Test
    fun byteUpdatesKeepIdentityAndMarkStatsOnly() {
        val old = RequestFlowData(id = "1", createdAt = 9L, uploadBytes = 1, downloadBytes = 2, closed = false)
        val next = old.copy(uploadBytes = 8, downloadBytes = 9, closed = true, closedAt = 10L)
        assertTrue(RequestFlowDiff.sameItem(old, next))
        assertFalse(RequestFlowDiff.sameContent(old, next))
        assertTrue(RequestFlowDiff.statsOnly(old, next))
    }
}

class RequestAppLabelCacheTest {
    @Test
    fun resolveHitsAreCachedAndBounded() {
        var lookups = 0
        val cache = RequestAppLabelCache(maxEntries = 2) { pkg ->
            lookups++
            "label-$pkg"
        }
        assertEquals("a.b", cache.display("a.b"))
        assertEquals(1, cache.resolveMissing(listOf("a.b", "a.b")))
        assertEquals("label-a.b", cache.display("a.b"))
        assertEquals(0, cache.resolveMissing(listOf("a.b")))
        assertEquals(1, lookups)
        cache.resolveMissing(listOf("c.d", "e.f"))
        assertEquals(null, cache.peek("a.b"))
    }
}

class RequestStoreSkipTest {
    @Test
    fun identicalSnapshotDoesNotNotify() {
        var notes = 0
        val listener = { notes++; Unit }
        RequestStore.resetGenerationFence()
        RequestStore.clear()
        RequestStore.addListener(listener)
        val row = RequestFlowData(id = "1", domain = "a.com")
        RequestStore.replace(listOf(row))
        RequestStore.replace(listOf(row.copy()))
        RequestStore.removeListener(listener)
        RequestStore.clear()
        assertEquals(1, notes)
    }
}
