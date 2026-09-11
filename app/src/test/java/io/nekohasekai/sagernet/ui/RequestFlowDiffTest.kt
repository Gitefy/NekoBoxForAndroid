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

class RequestRowSelectionTest {
    @Test
    fun payloadUpdateIsVisibleToClickSelection() {
        val first = RequestFlowData(id = "c1", createdAt = 9L, uploadBytes = 1, closed = false)
        val updated = first.copy(uploadBytes = 80, closed = true, closedAt = 11L)
        assertEquals(first, RequestRowSelection.select(0, listOf(first)))
        assertEquals(updated, RequestRowSelection.select(0, listOf(updated)))
        assertEquals("c1", RequestRowSelection.select(0, listOf(updated))?.id)
        assertTrue(RequestRowSelection.select(0, listOf(updated))!!.closed)
        assertEquals(80L, RequestRowSelection.select(0, listOf(updated))!!.uploadBytes)
    }

    @Test
    fun removedOrUnknownPositionDoesNotSelectWrongRow() {
        val a = RequestFlowData(id = "a", createdAt = 1L)
        val b = RequestFlowData(id = "b", createdAt = 2L)
        assertEquals(null, RequestRowSelection.select(RequestRowSelection.NO_POSITION, listOf(a, b)))
        assertEquals(null, RequestRowSelection.select(0, emptyList()))
        assertEquals(null, RequestRowSelection.select(5, listOf(a, b)))
        assertEquals("b", RequestRowSelection.select(0, listOf(b))?.id)
        assertEquals("a", RequestRowSelection.select(1, listOf(b, a))?.id)
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
