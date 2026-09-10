package io.nekohasekai.sagernet.database.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P01 regression coverage for [KvMemoryCache].
 *
 * The cache is what lets Room drop `allowMainThreadQueries`: getters must be
 * served from memory, writers must observe read-your-writes synchronously,
 * and cross-process DB snapshots must never clobber local in-flight writes.
 */
class KvMemoryCacheTest {

    private fun row(key: String, value: String): KeyValuePair =
        KeyValuePair(key).put(value)

    @Test
    fun readsAreServedFromMemoryAfterPrime() {
        val cache = KvMemoryCache()
        assertFalse(cache.isPrimed)

        cache.prime(listOf(row("a", "1"), row("b", "2")))
        assertTrue(cache.isPrimed)
        assertEquals("1", cache.get("a")?.string)
        assertEquals("2", cache.get("b")?.string)
        assertNull(cache.get("missing"))
    }

    @Test
    fun localPutsAreImmediatelyVisible() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        cache.put(row("k", "v"))
        assertEquals("v", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "v"))
        assertEquals("v", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun deleteIsImmediatelyVisibleAndSurvivesRequery() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "v")))

        cache.delete("k")
        assertNull(cache.get("k"))

        cache.writeCommitted("k", null)
        assertNull(cache.get("k"))
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun resetClearsLocalStateInstantly() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1"), row("b", "2")))

        cache.reset()
        assertTrue(cache.snapshot().isEmpty())
        assertTrue(cache.hasPending(KvMemoryCache.PENDING_RESET))
        assertNull(cache.get("a"))

        cache.writeCommitted(KvMemoryCache.PENDING_RESET, null)
        assertFalse(cache.hasPending(KvMemoryCache.PENDING_RESET))
    }

    @Test
    fun remoteSnapshotDoesNotClobberInFlightWrites() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("stable", "old")))

        // Local write still in flight (not yet acknowledged by the DB layer).
        cache.put(row("stable", "new"))
        cache.put(row("local-only", "x"))

        // Other process wrote "other" and rewrote "stable" with a stale copy.
        cache.merge(listOf(row("stable", "old"), row("other", "o")))

        assertEquals("new", cache.get("stable")?.string)
        assertEquals("x", cache.get("local-only")?.string)
        assertEquals("o", cache.get("other")?.string)
    }

    @Test
    fun remoteSnapshotRemovesKeysDeletedElsewhere() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1"), row("gone", "x")))

        cache.merge(listOf(row("a", "1")))
        assertNull(cache.get("gone"))
        assertEquals("1", cache.get("a")?.string)
    }

    @Test
    fun inFlightResetDefeatsRemoteSnapshot() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        cache.reset()
        // Stale full-table snapshot arriving between reset and commit.
        cache.merge(listOf(row("a", "1"), row("b", "2")))

        assertTrue(cache.snapshot().isEmpty())
        cache.writeCommitted(KvMemoryCache.PENDING_RESET, null)
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun snapshotExposesReadYourWritesStateForDumps() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        cache.put(row("b", "2"))
        cache.delete("a")

        val dumped = cache.snapshot().associate { it.key to it.string }
        assertEquals(mapOf("b" to "2"), dumped)
    }

    @Test
    fun pendingPutSurvivesMultipleRemoteMerges() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))
        cache.put(row("k", "new"))
        cache.merge(listOf(row("k", "old")))
        assertEquals("new", cache.get("k")?.string)
        cache.merge(listOf(row("k", "old")))
        assertEquals("new", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))
        cache.writeCommitted("k", row("k", "new"))
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun resetInflightIgnoresAllRemoteSnapshotsUntilCommitted() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1"), row("b", "2")))
        cache.reset()
        cache.merge(listOf(row("a", "1")))
        cache.merge(listOf(row("a", "1"), row("b", "2"), row("c", "3")))
        assertTrue(cache.snapshot().isEmpty())
        assertTrue(cache.hasPending(KvMemoryCache.PENDING_RESET))
        cache.writeCommitted(KvMemoryCache.PENDING_RESET, null)
        cache.merge(listOf(row("x", "9")))
        assertEquals("9", cache.get("x")?.string)
    }

    /**
     * S1-B1: an acknowledgment of a superseded put must not regress the mirror
     * to the older value, and must not clear the pending state of the newer
     * mutation. This regression test fails on the P01 baseline
     * (cc63f248): the stale ack overwrites v2 with v1 and drops pending.
     */
    @Test
    fun staleAckOfSupersededPutDoesNotRegressMemory() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        cache.put(row("k", "v1"))
        cache.put(row("k", "v2"))

        cache.writeCommitted("k", row("k", "v1"))
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "v2"))
        assertEquals("v2", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    /**
     * S1-B1: an acknowledgment of a put that was superseded by a delete must
     * not resurrect the deleted value, and must not clear the delete's pending
     * state. Fails on cc63f248: the stale ack re-inserts v1 into the mirror.
     */
    @Test
    fun staleAckOfPutDoesNotResurrectDeletedKey() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        cache.put(row("k", "v1"))
        cache.delete("k")

        cache.writeCommitted("k", row("k", "v1"))
        assertNull(cache.get("k"))
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", null)
        assertNull(cache.get("k"))
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun writeFailureKeepsMemoryAndAllowsRetryPut() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())
        cache.put(row("k", "v1"))
        assertEquals("v1", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))
        // Simulate DB write failure: do NOT call writeCommitted, memory stays pending.
        cache.merge(emptyList())
        assertEquals("v1", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))
        // Second put overwrites memory and stays pending; next commit clears.
        cache.put(row("k", "v2"))
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))
        cache.writeCommitted("k", row("k", "v2"))
        assertEquals("v2", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun deleteThenPutOrderingPinsLatestValue() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        cache.put(row("k", "v1"))
        cache.delete("k")
        cache.put(row("k", "v2"))

        // Stale ack of the first put must not resurrect v1 or clear the
        // delete/put pending chain; the tombstone ack must not erase v2.
        cache.writeCommitted("k", row("k", "v1"))
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", null)
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "v2"))
        assertEquals("v2", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun duplicateLegacyAckIsIdempotent() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        cache.put(row("k", "v"))
        cache.writeCommitted("k", row("k", "v"))
        assertFalse(cache.hasPending("k"))

        // Repeated acknowledgment must be a no-op.
        cache.writeCommitted("k", row("k", "v"))
        assertEquals("v", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun resetThenOldLegacyPutAckDoesNotResurrect() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        cache.put(row("k", "v"))
        cache.reset()
        assertTrue(cache.snapshot().isEmpty())

        // Ack of the pre-reset put arrives after the wipe; it must not
        // resurrect the value or clear the in-flight reset sentinel.
        cache.writeCommitted("k", row("k", "v"))
        assertTrue(cache.snapshot().isEmpty())
        assertTrue(cache.hasPending(KvMemoryCache.PENDING_RESET))
    }

    @Test
    fun snapshotRowsAreDefensiveCopies() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        val dumped = cache.snapshot()
        assertEquals(1, dumped.size)
        dumped[0].put("mutated")

        // Mutating a returned row must not leak into the mirror.
        assertEquals("1", cache.get("a")?.string)
        assertEquals("1", cache.snapshot()[0].string)
    }
}
