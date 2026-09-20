package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupReloadBatcherTest {
    @Test
    fun firstEventAlwaysFiresThenHonorsIntervalUntilFlush() {
        var now = 1_000L
        val batcher = GroupReloadBatcher(minIntervalMs = 300L) { now }
        assertTrue(batcher.shouldReloadNow())
        now = 1_100L
        assertFalse(batcher.shouldReloadNow())
        now = 1_299L
        assertFalse(batcher.shouldReloadNow())
        now = 1_300L
        assertTrue(batcher.shouldReloadNow())
        now = 1_400L
        assertFalse(batcher.shouldReloadNow())
        batcher.markFlushed()
        now = 1_400L
        assertFalse(batcher.shouldReloadNow())
        now = 1_700L
        assertTrue(batcher.shouldReloadNow())
    }

    @Test
    fun manyRapidEventsOnlyAllowSparseReloads() {
        var now = 0L
        val batcher = GroupReloadBatcher(minIntervalMs = 300L) { now }
        var allowed = 0
        repeat(50) {
            if (batcher.shouldReloadNow()) allowed++
            now += 10L
        }
        assertEquals(2, allowed)
        batcher.markFlushed()
        assertFalse(batcher.shouldReloadNow())
    }
}
