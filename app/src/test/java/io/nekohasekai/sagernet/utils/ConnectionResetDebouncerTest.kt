package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConnectionResetDebouncerTest {

    private var now = 0L
    private var fires = 0
    private val due = mutableListOf<Pair<Long, () -> Unit>>()

    @Before
    fun setup() {
        ConnectionResetDebouncer.resetForTest()
        now = 0L
        fires = 0
        due.clear()
        ConnectionResetDebouncer.clock = { now }
        ConnectionResetDebouncer.performer = { fires++ }
        ConnectionResetDebouncer.schedule = { delay, action -> due += (now + delay) to action }
    }

    private fun advance(ms: Long) {
        now += ms
        val snapshot = due.toList()
        due.clear()
        val ready = snapshot.filter { it.first <= now }.sortedBy { it.first }
        due.addAll(snapshot.filter { it.first > now })
        ready.forEach { it.second.invoke() }
    }

    @Test
    fun trailingEdgeFiresOnceAfterFlaps() {
        ConnectionResetDebouncer.resetAllConnections()
        ConnectionResetDebouncer.resetAllConnections()
        ConnectionResetDebouncer.resetAllConnections()
        assertEquals(0, fires)
        advance(ConnectionResetDebouncer.COALESCE_MS)
        assertEquals(1, fires)
    }

    @Test
    fun finalEventAfterGoThrottleStillFires() {
        ConnectionResetDebouncer.resetAllConnections()
        advance(ConnectionResetDebouncer.COALESCE_MS)
        assertEquals(1, fires)
        ConnectionResetDebouncer.resetAllConnections()
        advance(ConnectionResetDebouncer.COALESCE_MS)
        assertEquals(1, fires)
        advance(ConnectionResetDebouncer.GO_THROTTLE_MS)
        assertEquals(2, fires)
    }

    @Test
    fun forcedResetIsNotSwallowedByCoalesce() {
        ConnectionResetDebouncer.resetAllConnections()
        ConnectionResetDebouncer.resetAllConnections(force = true)
        advance(0)
        assertEquals(1, fires)
    }
}
