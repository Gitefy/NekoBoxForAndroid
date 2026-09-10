package io.nekohasekai.sagernet.database.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-B1 linearizability coverage for [KvMemoryCache].
 *
 * Two mechanisms protect the mirror from stale inputs:
 *
 * 1. Every local mutation ([put]/[delete]) returns a monotonic generation.
 *    [KvMemoryCache.writeCommitted] with a generation only acknowledges that
 *    exact mutation: an older generation's ACK can neither regress the
 *    current value nor clear the pending state of a newer mutation.
 * 2. Snapshot merges carry the epoch captured *before* the database read
 *    ([KvMemoryCache.captureReadEpoch]). Local writes that committed after
 *    that epoch are still newer than the snapshot, so the merge must not roll
 *    them back.
 *
 * These tests cannot compile against the P01 baseline (cc63f248), which has
 * no generation/epoch API; the equivalent behavior-level failures are pinned
 * in [KvMemoryCacheTest] (staleAckOfSupersededPutDoesNotRegressMemory and
 * staleAckOfPutDoesNotResurrectDeletedKey).
 */
class KvMemoryCacheLinearizabilityTest {

    private fun row(key: String, value: String): KeyValuePair =
        KeyValuePair(key).put(value)

    @Test
    fun staleGenerationAckDoesNotRegressSupersededPut() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        val first = cache.put(row("k", "v1"))
        val second = cache.put(row("k", "v2"))

        cache.writeCommitted("k", row("k", "v1"), first)
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "v2"), second)
        assertEquals("v2", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun staleGenerationAckDoesNotResurrectDeletedKey() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        val putGeneration = cache.put(row("k", "v1"))
        val deleteGeneration = cache.delete("k")

        cache.writeCommitted("k", row("k", "v1"), putGeneration)
        assertNull(cache.get("k"))
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", null, deleteGeneration)
        assertNull(cache.get("k"))
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun sameValueRewriteStaysPendingUntilItsOwnGenerationIsAcknowledged() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        val first = cache.put(row("k", "same"))
        val second = cache.put(row("k", "same"))

        cache.writeCommitted("k", row("k", "same"), first)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "same"), second)
        assertFalse(cache.hasPending("k"))
        assertEquals("same", cache.get("k")?.string)
    }

    @Test
    fun snapshotReadBeforeWriteMergedAfterCommitDoesNotRollBack() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("other", "o")))

        // Database read starts here; the snapshot does not contain "k".
        val readEpoch = cache.captureReadEpoch()
        val staleSnapshot = listOf(row("other", "o"))

        cache.put(row("k", "v2"))
        cache.writeCommitted("k", row("k", "v2"))

        cache.merge(staleSnapshot, readEpoch)
        assertEquals("v2", cache.get("k")?.string)
        assertEquals("o", cache.get("other")?.string)
    }

    @Test
    fun snapshotWithStaleRowMergedAfterCommitDoesNotRollBack() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val readEpoch = cache.captureReadEpoch()
        cache.put(row("k", "v2"))
        cache.writeCommitted("k", row("k", "v2"))

        cache.merge(listOf(row("k", "old")), readEpoch)
        assertEquals("v2", cache.get("k")?.string)
    }

    @Test
    fun freshSnapshotAfterCommitAppliesCrossProcessValue() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val readEpoch = cache.captureReadEpoch()
        cache.put(row("k", "v2"))
        cache.writeCommitted("k", row("k", "v2"))
        cache.merge(listOf(row("k", "old")), readEpoch)
        assertEquals("v2", cache.get("k")?.string)

        // A later snapshot, read after the commit, must propagate remote state.
        val freshEpoch = cache.captureReadEpoch()
        cache.merge(listOf(row("k", "remote")), freshEpoch)
        assertEquals("remote", cache.get("k")?.string)
    }

    @Test
    fun inFlightLocalWriteStillBeatsFreshSnapshot() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val freshEpoch = cache.captureReadEpoch()
        cache.put(row("k", "local"))
        cache.merge(listOf(row("k", "remote")), freshEpoch)
        assertEquals("local", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))
    }

    @Test
    fun deleteThenPutGenerationsAckedIndependently() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        val putGeneration = cache.put(row("k", "v1"))
        val deleteGeneration = cache.delete("k")
        val rePutGeneration = cache.put(row("k", "v2"))

        cache.writeCommitted("k", row("k", "v1"), putGeneration)
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", null, deleteGeneration)
        assertEquals("v2", cache.get("k")?.string)
        assertTrue(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "v2"), rePutGeneration)
        assertEquals("v2", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun duplicateGenerationAckIsIdempotent() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        val generation = cache.put(row("k", "v"))
        cache.writeCommitted("k", row("k", "v"), generation)
        assertFalse(cache.hasPending("k"))

        cache.writeCommitted("k", row("k", "v"), generation)
        assertEquals("v", cache.get("k")?.string)
        assertFalse(cache.hasPending("k"))
    }

    @Test
    fun oldGenerationAckAfterResetDoesNotResurrect() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        val generation = cache.put(row("k", "v"))
        cache.reset()
        assertTrue(cache.snapshot().isEmpty())

        cache.writeCommitted("k", row("k", "v"), generation)
        assertTrue(cache.snapshot().isEmpty())
        assertTrue(cache.hasPending(KvMemoryCache.PENDING_RESET))
    }
}