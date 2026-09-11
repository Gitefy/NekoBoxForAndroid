package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionObserverTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pageVisibleStartsPolling() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        var polls = 0
        val observer = ConnectionObserver(
            snapshot = { polls++; """{"flows":[]}""" },
            publish = {},
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            intervalMs = 1L,
            wait = { ticks.receive() },
            scope = this,
        )
        observer.start()
        yield()
        ticks.send(Unit)
        yield()
        assertTrue(observer.enabled)
        assertTrue(polls >= 1)
        observer.stop()
    }

    @Test
    fun pageHiddenStopsPolling() = runBlocking {
        val gate = Channel<Unit>()
        val observer = ConnectionObserver(
            snapshot = { """{"flows":[]}""" },
            publish = {},
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            wait = { gate.receive() },
            scope = this,
        )
        observer.start()
        observer.stop()
        assertFalse(observer.enabled)
        assertFalse(observer.pollActive)
        assertTrue(gate.isEmpty)
    }

    @Test
    fun oldRuntimeCannotPublishIntoNewRuntime() = runBlocking {
        var current = 1L
        val published = ArrayList<Long>()
        val old = ConnectionObserver(
            snapshot = { """{"flows":[{"id":"old","logicalOutbound":"x","finalOutboundTag":"y"}]}""" },
            publish = { published.add(it.runtimeGeneration) },
            isCurrent = { current == 1L },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )
        val newer = ConnectionObserver(
            snapshot = { """{"flows":[{"id":"new","logicalOutbound":"x","finalOutboundTag":"y"}]}""" },
            publish = { published.add(it.runtimeGeneration) },
            isCurrent = { current == 2L },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 2L,
            scope = this,
        )
        current = 2L
        assertFalse(old.pollOnce())
        assertTrue(newer.pollOnce())
        assertEquals(listOf(2L), published)
    }

    @Test
    fun snapshotFailureDoesNotStopVpn() = runBlocking {
        var vpnAlive = true
        val observer = ConnectionObserver(
            snapshot = { error("boom") },
            publish = { vpnAlive = false },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )
        assertFalse(observer.pollOnce())
        assertTrue(vpnAlive)
        assertEquals("boom", observer.lastFailure)
    }
}

class RequestFlowMapperTest {
    @Test
    fun routerAndFinalProfileAreMappedSeparately() {
        val maps = RequestDisplayMaps.fromRuntime(
            routerSelectorTags = mapOf("US" to "router-us"),
            stableToName = mapOf("US" to "Router-US"),
            tagToProfileName = mapOf("us-la-03" to "US-LA-03"),
        )
        val mapped = RequestFlowMapper.map(
            RequestFlowData(id = "1", logicalOutbound = "router-us", finalOutboundTag = "us-la-03"),
            maps,
        )
        assertEquals("US", mapped.routerStableTag)
        assertEquals("Router-US", mapped.routerName)
        assertEquals("US-LA-03", mapped.finalProfileName)
        assertEquals(RequestFlowMapper.KIND_PROXY, mapped.kind)
    }

    @Test
    fun directConnectionDisplaysDirect() {
        val mapped = RequestFlowMapper.map(
            RequestFlowData(id = "d", logicalOutbound = "direct", finalOutboundTag = "direct"),
            RequestDisplayMaps(),
        )
        assertEquals("DIRECT", mapped.finalProfileName)
        assertEquals(RequestFlowMapper.KIND_DIRECT, mapped.kind)
    }

    @Test
    fun unknownTagFallsBackToRawTag() {
        val mapped = RequestFlowMapper.map(
            RequestFlowData(id = "u", logicalOutbound = "mystery", finalOutboundTag = "leaf-x"),
            RequestDisplayMaps(),
        )
        assertEquals("leaf-x", mapped.finalProfileName)
        assertEquals("", mapped.routerStableTag)
    }
}
