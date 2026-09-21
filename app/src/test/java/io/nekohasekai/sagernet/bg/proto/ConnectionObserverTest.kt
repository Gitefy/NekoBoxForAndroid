package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
        old.start()
        newer.start()
        delay(30)
        assertFalse(old.pollOnce())
        assertTrue(newer.pollOnce())
        delay(30)
        assertEquals(setOf(2L), published.toSet())
        old.stop()
        newer.stop()
    }

    @Test
    fun mapsAreCachedAcrossPolls() = runBlocking {
        var mapCalls = 0
        val observer = ConnectionObserver(
            snapshot = { """{"flows":[{"id":"a","logicalOutbound":"x","finalOutboundTag":"y"},{"id":"b","logicalOutbound":"x","finalOutboundTag":"y"}]}""" },
            publish = {},
            isCurrent = { true },
            maps = { mapCalls++; RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )
        observer.start()
        yield()
        assertTrue(observer.pollOnce())
        assertEquals(1, mapCalls)
        observer.stop()
    }

    @Test
    fun publishIsSkippedWhenRuntimeBecomesStaleAfterMapping() = runBlocking {
        var current = true
        val published = ArrayList<Long>()
        val observer = ConnectionObserver(
            snapshot = { """{"flows":[{"id":"x","logicalOutbound":"a","finalOutboundTag":"b"}]}""" },
            publish = { published.add(it.runtimeGeneration) },
            isCurrent = { current },
            maps = {
                current = false
                RequestDisplayMaps()
            },
            runtimeGeneration = 1L,
            scope = this,
        )
        observer.start()
        yield()
        assertFalse(observer.pollOnce())
        assertTrue(published.isEmpty())
        observer.stop()
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
        observer.start()
        yield()
        assertFalse(observer.pollOnce())
        assertTrue(vpnAlive)
        assertEquals("boom", observer.lastFailure)
        observer.stop()
    }

    @Test
    fun duplicateSnapshotIsNotPublishedTwice() = runBlocking {
        val published = ArrayList<String>()
        val observer = ConnectionObserver(
            snapshot = { """{"flows":[{"id":"a","createdAt":1,"uploadBytes":1,"logicalOutbound":"x","finalOutboundTag":"y"}]}""" },
            publish = { published.add(it.items.first().id) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )
        observer.start()
        delay(30)
        assertTrue(observer.pollOnce())
        delay(30)
        assertEquals(listOf("a"), published)
        observer.stop()
    }

    @Test
    fun unchangedRawSnapshotStillPollsButDoesNotRepublish() = runBlocking {
        var snapshots = 0
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                """{"flows":[{"id":"a","createdAt":1,"uploadBytes":1,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )
        observer.start()
        delay(30)
        val afterStart = snapshots
        assertTrue(afterStart >= 1)
        assertTrue(observer.pollOnce())
        assertTrue(observer.pollOnce())
        assertEquals(afterStart + 2, snapshots)
        assertEquals(1, published.size)
        observer.stop()
    }

    @Test
    fun duplicateEnableStaysOnSerialSamplerAndResendsFirstFrame() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val published = ArrayList<Int>()
        var polls = 0
        val observer = ConnectionObserver(
            snapshot = {
                polls++
                val n = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                try {
                    """{"flows":[{"id":"a","createdAt":1,"uploadBytes":1,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
                } finally {
                    inFlight.decrementAndGet()
                }
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            wait = { ticks.receive() },
            scope = this,
        )
        observer.start()
        yield()
        val pollsAfterStart = polls
        assertTrue(pollsAfterStart >= 1)
        observer.start()
        yield()
        yield()
        assertTrue(polls > pollsAfterStart)
        assertEquals(1, maxInFlight.get())
        assertTrue(published.size >= 2)
        observer.stop()
    }

    @Test
    fun stopStartWithIdenticalSnapshotRepublishesFirstFrame() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val published = ArrayList<Int>()
        val raw =
            """{"flows":[{"id":"a","createdAt":1,"uploadBytes":1,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
        val observer = ConnectionObserver(
            snapshot = { raw },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            wait = { ticks.receive() },
            scope = this,
        )
        observer.start()
        delay(30)
        assertEquals(1, published.size)
        assertTrue(observer.pollOnce())
        delay(30)
        assertEquals(1, published.size)
        observer.stop()
        observer.start()
        delay(30)
        assertEquals(2, published.size)
        observer.stop()
    }

    @Test
    fun emptySnapshotSessionResetStillPublishesFirstFrame() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = { """{"flows":[]}""" },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            wait = { ticks.receive() },
            scope = this,
        )
        observer.start()
        delay(30)
        assertEquals(1, published.size)
        assertEquals(0, published.single())
        assertTrue(observer.pollOnce())
        delay(30)
        assertEquals(1, published.size)
        observer.stop()
        observer.start()
        delay(30)
        assertEquals(2, published.size)
        observer.stop()
    }

    @Test
    fun newObserverReconnectsWithSameSnapshotStillPublishes() = runBlocking {
        val published = ArrayList<String>()
        val raw =
            """{"flows":[{"id":"a","createdAt":1,"uploadBytes":1,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
        fun observer() = ConnectionObserver(
            snapshot = { raw },
            publish = { published.add(it.items.first().id) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            wait = { kotlinx.coroutines.channels.Channel<Unit>().receive() },
            scope = this,
        )
        val sessionA = observer()
        sessionA.start()
        delay(30)
        assertEquals(listOf("a"), published)
        assertTrue(sessionA.pollOnce())
        delay(30)
        assertEquals(listOf("a"), published)
        sessionA.stop()
        val sessionB = observer()
        sessionB.start()
        delay(30)
        assertEquals(listOf("a", "a"), published)
        sessionB.stop()
    }

    @Test
    fun firstSnapshotPublishesEvenWithKnownRevision() = runBlocking {
        var snapshots = 0
        val published = ArrayList<String>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                """{"flows":[{"id":"flow-1","createdAt":1,"uploadBytes":100,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
            },
            publish = { published.add(it.items.first().id) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { 42L },
        )
        observer.start()
        delay(30)
        assertEquals(1, snapshots)
        assertEquals(listOf("flow-1"), published)
        observer.stop()
    }

    @Test
    fun identicalRevisionSkipsSnapshotCallEntirely() = runBlocking {
        var snapshots = 0
        var currentRevision = 10L
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                """{"flows":[{"id":"flow-1","createdAt":1,"uploadBytes":100,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { currentRevision },
        )
        observer.start()
        delay(30)
        assertEquals(1, snapshots)
        assertEquals(1, published.size)

        // Poll again with unchanged revision
        assertTrue(observer.pollOnce())
        assertTrue(observer.pollOnce())
        // snapshot() MUST NOT be called!
        assertEquals(1, snapshots)
        assertEquals(1, published.size)
        observer.stop()
    }

    @Test
    fun changedRevisionCallsSnapshotAndPublishes() = runBlocking {
        var snapshots = 0
        var currentRevision = 1L
        var raw = """{"flows":[{"id":"f1","createdAt":1,"uploadBytes":100,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
        val published = ArrayList<Long>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                raw
            },
            publish = { published.add(it.items.first().uploadBytes) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { currentRevision },
        )
        observer.start()
        delay(30)
        assertEquals(1, snapshots)
        assertEquals(listOf(100L), published)

        // Advance byte counters and revision
        currentRevision = 2L
        raw = """{"flows":[{"id":"f1","createdAt":1,"uploadBytes":250,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
        assertTrue(observer.pollOnce())
        delay(30)
        assertEquals(2, snapshots)
        assertEquals(listOf(100L, 250L), published)
        observer.stop()
    }

    @Test
    fun stopStartResetsRevisionAndRepublishesFirstFrame() = runBlocking {
        var snapshots = 0
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                """{"flows":[{"id":"f1","createdAt":1,"uploadBytes":100,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { 77L }, // constant revision across sessions
        )
        observer.start()
        delay(30)
        assertEquals(1, snapshots)
        assertEquals(1, published.size)

        // Stop observer (page invisible)
        observer.stop()

        // Start observer again (page visible): must republish first frame even with identical revision 77L
        observer.start()
        delay(30)
        assertEquals(2, snapshots)
        assertEquals(2, published.size)
        observer.stop()
    }

    @Test
    fun newObserverSessionTokenCollisionPublishesFirstFrame() = runBlocking {
        val published = ArrayList<String>()
        val raw = """{"flows":[{"id":"flow-a","createdAt":1,"uploadBytes":10,"logicalOutbound":"x","finalOutboundTag":"y"}]}"""

        fun makeObserver() = ConnectionObserver(
            snapshot = { raw },
            publish = { published.add(it.items.first().id) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            wait = { Channel<Unit>().receive() },
            scope = this,
            revision = { 1L }, // Both sessions happen to have token/revision = 1L
        )

        val sessionA = makeObserver()
        sessionA.start()
        delay(30)
        assertEquals(listOf("flow-a"), published)
        sessionA.stop()

        val sessionB = makeObserver()
        sessionB.start()
        delay(30)
        assertEquals(listOf("flow-a", "flow-a"), published)
        sessionB.stop()
    }

    @Test
    fun emptySnapshotWithIdenticalRevisionSkips() = runBlocking {
        var snapshots = 0
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                """{"flows":[]}"""
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { 0L },
        )
        observer.start()
        delay(30)
        assertEquals(1, snapshots)
        assertEquals(listOf(0), published)

        // Poll with same 0L revision
        assertTrue(observer.pollOnce())
        assertTrue(observer.pollOnce())
        assertEquals(1, snapshots)
        assertEquals(1, published.size)
        observer.stop()
    }

    @Test
    fun nullRevisionFallsBackToSnapshotAndDedup() = runBlocking {
        var snapshots = 0
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = {
                snapshots++
                """{"flows":[{"id":"x","createdAt":1,"uploadBytes":1,"logicalOutbound":"a","finalOutboundTag":"b"}]}"""
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { null }, // null revision fallback
        )
        observer.start()
        delay(30)
        assertEquals(1, snapshots)
        assertEquals(1, published.size)

        // Poll again: calls snapshot, but P2-A dedup skips publication
        assertTrue(observer.pollOnce())
        assertEquals(2, snapshots)
        assertEquals(1, published.size)
        observer.stop()
    }

    @Test
    fun snapshotFailureDoesNotAdvanceRevision() = runBlocking {
        var currentRevision = 1L
        var shouldFail = false
        val published = ArrayList<Int>()
        val observer = ConnectionObserver(
            snapshot = {
                if (shouldFail) error("injected failure")
                """{"flows":[{"id":"f1","createdAt":1,"uploadBytes":1,"logicalOutbound":"a","finalOutboundTag":"b"}]}"""
            },
            publish = { published.add(it.items.size) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
            revision = { currentRevision },
        )
        observer.start()
        delay(30)
        assertEquals(1, published.size)

        // Advance revision but snapshot fails
        currentRevision = 2L
        shouldFail = true
        assertFalse(observer.pollOnce())

        // Next poll when snapshot succeeds must still attempt to fetch since lastRevision was not updated to 2L
        shouldFail = false
        assertTrue(observer.pollOnce())
        observer.stop()
    }

    @Test
    fun singleJniSnapshotSinceOnlyInvokedOnceOnChangedAndUnchangedTicks() = runBlocking {
        var snapshotSinceCalls = 0
        var legacySnapshotCalls = 0
        var legacyRevisionCalls = 0
        val published = ArrayList<String>()

        var currentRev = 100L
        var isUnchanged = false
        var resp = ConnectionObserver.SnapshotResult(
            revision = 100L,
            unchanged = false,
            payload = """{"flows":[{"id":"flow-1","logicalOutbound":"p","finalOutboundTag":"f"}]}""",
        )

        val observer = ConnectionObserver(
            snapshot = { legacySnapshotCalls++; "" },
            revision = { legacyRevisionCalls++; 0L },
            snapshotSince = { lastRev ->
                snapshotSinceCalls++
                if (lastRev == currentRev) {
                    ConnectionObserver.SnapshotResult(
                        revision = currentRev,
                        unchanged = true,
                        payload = "",
                    )
                } else {
                    resp
                }
            },
            publish = { batch -> published.add(batch.items.first().id) },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )

        observer.start()
        delay(30)

        // Poll 1: Initial frame
        assertEquals(1, snapshotSinceCalls)
        assertEquals(0, legacySnapshotCalls)
        assertEquals(0, legacyRevisionCalls)
        assertEquals(1, published.size)
        assertEquals("flow-1", published[0])

        // Poll 2: Revision unchanged -> single JNI, no publish
        val res = observer.pollOnce()
        assertTrue(res)
        assertEquals(2, snapshotSinceCalls)
        assertEquals(0, legacySnapshotCalls)
        assertEquals(0, legacyRevisionCalls)
        assertEquals(1, published.size)

        // Poll 3: Revision advances
        currentRev = 101L
        resp = ConnectionObserver.SnapshotResult(
            revision = 101L,
            unchanged = false,
            payload = """{"flows":[{"id":"flow-2","logicalOutbound":"p","finalOutboundTag":"f"}]}""",
        )
        val res3 = observer.pollOnce()
        assertTrue(res3)
        delay(30)
        assertEquals(3, snapshotSinceCalls)
        assertEquals(2, published.size)
        assertEquals("flow-2", published[1])

        observer.stop()
    }

    @Test
    fun snapshotSinceSessionRestartForcesFullFrame() = runBlocking {
        var reqRevReceived: Long? = null
        val resp = ConnectionObserver.SnapshotResult(
            revision = 42L,
            unchanged = false,
            payload = """{"flows":[{"id":"f","logicalOutbound":"p","finalOutboundTag":"f"}]}""",
        )

        val observer = ConnectionObserver(
            snapshot = { "" },
            snapshotSince = { lastRev ->
                reqRevReceived = lastRev
                resp
            },
            publish = {},
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )

        observer.start()
        delay(30)
        assertEquals(-1L, reqRevReceived)

        // Run pollOnce to update lastRevision to 42L
        observer.pollOnce()

        // Restart session: must reset lastRevision and send -1L again
        observer.stop()
        observer.start()
        delay(30)
        assertEquals(-1L, reqRevReceived)

        observer.stop()
    }

    @Test
    fun snapshotFailureDoesNotAdvanceRevisionAndNextPollRetries() = runBlocking {
        var reqRevPolled = -100L
        var returnFailure = false

        val observer = ConnectionObserver(
            snapshot = { "" },
            snapshotSince = { reqRev ->
                reqRevPolled = reqRev
                if (returnFailure) {
                    ConnectionObserver.SnapshotResult(
                        revision = 10L,
                        unchanged = false,
                        payload = "", // Blank payload simulates serialization failure
                    )
                } else {
                    ConnectionObserver.SnapshotResult(
                        revision = if (reqRev < 9L) 9L else 10L,
                        unchanged = false,
                        payload = """{"flows":[{"id":"flow-${reqRev}","logicalOutbound":"p","finalOutboundTag":"n"}]}""",
                    )
                }
            },
            publish = {},
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )

        observer.start()

        // 1. Initial poll -> reaches revision 9
        assertTrue(observer.pollOnce())
        assertEquals(-1L, reqRevPolled)

        // 2. Poll with failure -> revision 10 fails serialization, returns blank payload
        returnFailure = true
        assertFalse(observer.pollOnce())
        assertEquals(9L, reqRevPolled)

        // 3. Next poll -> reqRev MUST STILL BE 9 (not 10!), retrying revision 10
        returnFailure = false
        assertTrue(observer.pollOnce())
        assertEquals(9L, reqRevPolled) // Verifies rev 9 was retained and retried!

        // 4. Following poll -> now at rev 10
        assertTrue(observer.pollOnce())
        assertEquals(10L, reqRevPolled)

        observer.stop()
    }

    @Test
    fun normalPathHasZeroFallbackCount() = runBlocking {
        val observer = ConnectionObserver(
            snapshot = { "" },
            snapshotSince = {
                ConnectionObserver.SnapshotResult(
                    revision = 1L,
                    unchanged = false,
                    payload = """{"flows":[{"id":"f1","logicalOutbound":"p","finalOutboundTag":"n"}]}""",
                )
            },
            publish = {},
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )

        observer.start()
        observer.pollOnce()
        observer.pollOnce()
        assertEquals(0, observer.fallbackCount.get())
        observer.stop()
    }

    @Test
    fun multiRoundStartStopLifecycleStress() = runBlocking {
        var reqRevPolled = -100L
        var polls = 0
        var publishedCount = 0

        val observer = ConnectionObserver(
            snapshot = { "" },
            snapshotSince = { reqRev ->
                reqRevPolled = reqRev
                polls++
                ConnectionObserver.SnapshotResult(
                    revision = polls.toLong(),
                    unchanged = false,
                    payload = """{"flows":[{"id":"flow-$polls","logicalOutbound":"p","finalOutboundTag":"n"}]}""",
                )
            },
            publish = { publishedCount++ },
            isCurrent = { true },
            maps = { RequestDisplayMaps() },
            runtimeGeneration = 1L,
            scope = this,
        )

        // Run 5 cycles of start -> poll -> stop
        repeat(5) { cycle ->
            observer.start()
            assertTrue(observer.enabled)
            assertTrue(observer.pollActive)

            // Every new session MUST start with reqRev = -1L
            observer.pollOnce()
            assertEquals(-1L, reqRevPolled)

            // Subsequent poll in same session sends the previous revision
            val prevRev = polls.toLong()
            observer.pollOnce()
            assertEquals(prevRev, reqRevPolled)

            delay(10)
            observer.stop()
            assertFalse(observer.enabled)
            assertFalse(observer.pollActive)
        }

        delay(50)
        // Publisher should have cleanly delivered batches
        assertTrue(publishedCount >= 5)
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

class RequestFlowBinderBudgetTest {
    @Test
    fun threeHundredFlowsStayUnderBinderLimit() {
        assertTrue(io.nekohasekai.sagernet.aidl.RequestFlowBinderBudget.isWithinBinderLimit())
        assertTrue(
            io.nekohasekai.sagernet.aidl.RequestFlowBinderBudget.worstCasePayloadBytes() < 1_000_000
        )
    }
}
