package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSelectionBroadcastTest {
    @Test
    fun identicalContentIsSentOnlyOnceEvenWithNewArrayInstance() {
        val first = longArrayOf(10L, 2L, 20L, 4L)
        assertTrue(TrafficSelectionBroadcast.shouldSend(null, first))
        val stored = TrafficSelectionBroadcast.snapshot(first)
        assertFalse(TrafficSelectionBroadcast.shouldSend(stored, longArrayOf(10L, 2L, 20L, 4L)))
        assertFalse(TrafficSelectionBroadcast.shouldSend(stored, stored))
    }

    @Test
    fun contentChangeIsSent() {
        val last = longArrayOf(10L, 2L)
        assertTrue(TrafficSelectionBroadcast.shouldSend(last, longArrayOf(10L, 3L)))
    }

    @Test
    fun emptyToNonEmptyIsSent() {
        assertTrue(TrafficSelectionBroadcast.shouldSend(longArrayOf(), longArrayOf(10L, 2L)))
    }

    @Test
    fun nonEmptyToEmptyIsSent() {
        assertTrue(TrafficSelectionBroadcast.shouldSend(longArrayOf(10L, 2L), longArrayOf()))
    }

    @Test
    fun multiRouterPairChangeIsSent() {
        val last = longArrayOf(10L, 2L, 20L, 4L, 30L, 6L)
        assertFalse(TrafficSelectionBroadcast.shouldSend(last, longArrayOf(10L, 2L, 20L, 4L, 30L, 6L)))
        assertTrue(TrafficSelectionBroadcast.shouldSend(last, longArrayOf(10L, 2L, 20L, 5L, 30L, 6L)))
        assertTrue(TrafficSelectionBroadcast.shouldSend(last, longArrayOf(10L, 2L, 20L, 4L)))
    }

    @Test
    fun snapshotCopiesSoLaterMutationDoesNotAlias() {
        val live = longArrayOf(1L, 2L)
        val stored = TrafficSelectionBroadcast.snapshot(live)
        live[1] = 99L
        assertArrayEquals(longArrayOf(1L, 2L), stored)
        assertTrue(TrafficSelectionBroadcast.shouldSend(stored, live))
    }
}
