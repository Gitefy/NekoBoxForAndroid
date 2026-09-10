package io.nekohasekai.sagernet.database.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-B3 full-table cache fence contract (FD-1.0, S1-B3.md section 5).
 *
 * The fence advances the same monotonic clock as keyed generations, so a
 * snapshot whose read epoch predates a committed fence can never roll the
 * restored/reset table back. Stale fence callbacks (an older fence committing
 * or aborting while a newer fence is pending) must not clobber the newer
 * fence's state, watermark, or pendings.
 */
class KvMemoryCacheFullTableFenceTest {

    private fun row(key: String, value: String): KeyValuePair =
        KeyValuePair(key).put(value)

    /** A pre-fence write that never gets acknowledged stays pending and blocks remote rows. */
    @Test
    fun failedPendingWriteIsSupersededBySuccessfulRestore() {
        val cache = KvMemoryCache()
        cache.prime(emptyList())

        val failedGeneration = cache.put(row("k", "v1"))
        assertTrue(cache.hasPending("k"))

        val token = cache.beginFullTableFence(listOf(row("k", "restored")))
        cache.writeCommitted("k", row("k", "v1"), failedGeneration)
        assertEquals("restored", cache.get("k")?.string)

        cache.merge(listOf(row("k", "remote")), cache.captureReadEpoch())
        assertEquals("restored", cache.get("k")?.string)

        cache.commitFullTableFence(token, listOf(row("k", "restored")))
        cache.merge(listOf(row("k", "remote")), cache.captureReadEpoch())
        assertEquals("remote", cache.get("k")?.string)
    }

    @Test
    fun staleSnapshotStartedBeforeRestoreCannotRollbackRestore() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val staleEpoch = cache.captureReadEpoch()
        val token = cache.beginFullTableFence(listOf(row("k", "restored")))
        cache.commitFullTableFence(token, listOf(row("k", "restored")))

        cache.merge(listOf(row("k", "old")), staleEpoch)
        assertEquals("restored", cache.get("k")?.string)
    }

    @Test
    fun staleSnapshotStartedBeforeResetCannotResurrectRows() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1"), row("b", "2")))

        val staleEpoch = cache.captureReadEpoch()
        val token = cache.beginFullTableFence(emptyList())
        cache.commitFullTableFence(token, emptyList())

        cache.merge(listOf(row("a", "1"), row("b", "2")), staleEpoch)
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun postFenceKeyedWriteSurvivesFenceCommit() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        val token = cache.beginFullTableFence(listOf(row("a", "restored"), row("b", "keep")))
        val postFenceGeneration = cache.put(row("a", "edited"))
        assertTrue(cache.hasPending("a"))

        cache.commitFullTableFence(token, listOf(row("a", "restored"), row("b", "keep")))
        assertEquals("edited", cache.get("a")?.string)
        assertTrue(cache.hasPending("a"))

        cache.writeCommitted("a", row("a", "edited"), postFenceGeneration)
        assertEquals("edited", cache.get("a")?.string)
        assertFalse(cache.hasPending("a"))
        assertEquals("keep", cache.get("b")?.string)
    }

    @Test
    fun postFenceDeleteSurvivesFenceCommit() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1"), row("b", "2")))

        val token = cache.beginFullTableFence(listOf(row("a", "1"), row("b", "2")))
        val postFenceDelete = cache.delete("a")
        assertTrue(cache.hasPending("a"))
        assertNull(cache.get("a"))

        cache.commitFullTableFence(token, listOf(row("a", "1"), row("b", "2")))
        assertNull(cache.get("a"))
        assertTrue(cache.hasPending("a"))

        cache.writeCommitted("a", null, postFenceDelete)
        assertNull(cache.get("a"))
        assertFalse(cache.hasPending("a"))
        assertEquals("2", cache.get("b")?.string)
    }

    @Test
    fun freshSnapshotAfterFenceStillApplies() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val token = cache.beginFullTableFence(listOf(row("k", "restored")))
        cache.commitFullTableFence(token, listOf(row("k", "restored")))

        cache.merge(listOf(row("k", "fresh")), cache.captureReadEpoch())
        assertEquals("fresh", cache.get("k")?.string)
    }

    @Test
    fun snapshotMergeWhileFencePendingIsBlocked() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val token = cache.beginFullTableFence(listOf(row("k", "restored")))
        // Captured while the fence is pending: this merge must not interleave.
        val duringEpoch = cache.captureReadEpoch()
        assertEquals(token, duringEpoch)
        cache.merge(listOf(row("k", "old")), duringEpoch)
        assertEquals("restored", cache.get("k")?.string)

        cache.commitFullTableFence(token, listOf(row("k", "restored")))
        cache.merge(listOf(row("k", "fresh")), cache.captureReadEpoch())
        assertEquals("fresh", cache.get("k")?.string)
    }

    @Test
    fun staleFenceCallbacksCannotClobberNewerFence() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("a", "1")))

        val preFenceEpoch = cache.captureReadEpoch()
        val firstToken = cache.beginFullTableFence(listOf(row("a", "f1")))
        val secondToken = cache.beginFullTableFence(listOf(row("a", "f2")))

        // Older fence callback: must not publish, clear the newer pending, or move the watermark.
        cache.commitFullTableFence(firstToken, listOf(row("a", "f1")))
        assertEquals("f2", cache.get("a")?.string)
        cache.abortFullTableFence(firstToken)
        cache.merge(listOf(row("a", "old")), preFenceEpoch)
        assertEquals("f2", cache.get("a")?.string)

        cache.commitFullTableFence(secondToken, listOf(row("a", "f2")))
        assertEquals("f2", cache.get("a")?.string)

        // A callback after its own commit is stale as well: watermark must survive it.
        cache.abortFullTableFence(secondToken)
        cache.merge(listOf(row("a", "old")), preFenceEpoch)
        assertEquals("f2", cache.get("a")?.string)
    }

    @Test
    fun abortedFenceHoldsOptimisticRowsUntilOrderedRealign() {
        val cache = KvMemoryCache()
        cache.prime(listOf(row("k", "old")))

        val token = cache.beginFullTableFence(listOf(row("k", "restored")))
        cache.abortFullTableFence(token)
        // Optimistic rows stay after the abort (policy: hold, then realign by a
        // fresh ordered read); a stale snapshot must not realign it.
        assertEquals("restored", cache.get("k")?.string)
        cache.merge(listOf(row("k", "old")), cache.captureReadEpoch() - 1)
        assertEquals("restored", cache.get("k")?.string)

        cache.merge(listOf(row("k", "old")), cache.captureReadEpoch())
        assertEquals("old", cache.get("k")?.string)
    }
}
