package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BatchUrlTestRunnerTest {

    private fun socks(id: Long, host: String = "10.0.0.1", port: Int = 1080): ProxyEntity {
        return ProxyEntity(id = id, groupId = 1L, userOrder = id).apply {
            putBean(SOCKSBean().apply {
                serverAddress = host
                serverPort = port
                name = "socks-$id"
                applyDefaultValues()
            })
        }
    }

    private fun trojanGoPlugin(id: Long): ProxyEntity {
        return ProxyEntity(id = id, groupId = 1L, type = ProxyEntity.TYPE_TROJAN_GO).apply {
            putBean(TrojanGoBean().apply {
                serverAddress = "tg.example.com"
                serverPort = 443
                password = "pass"
                name = "tg-$id"
                applyDefaultValues()
            })
        }
    }

    class FakeBoxBridge(
        private val onProbe: (tag: String) -> Int = { 42 },
    ) : BoxBridge {
        val startCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val probedTags = CopyOnWriteArrayList<String>()

        override fun start() {
            startCount.incrementAndGet()
        }

        override fun close() {
            closeCount.incrementAndGet()
        }

        override fun urlTestWithTarget(link: String, timeout: Int, targetTag: String): Int {
            probedTags.add(targetTag)
            return onProbe(targetTag)
        }
    }

    @Test
    fun batchLifecycleZeroProfilesCreatesZeroBoxes() = runBlocking {
        var createCount = 0
        val fakeBridge = FakeBoxBridge()

        val runner = BatchUrlTestRunner(
            boxFactory = {
                createCount++
                fakeBridge
            },
        )

        runner.run(emptyList()) {}

        val metrics = runner.lastMetrics
        assertEquals(0, metrics.boxCreateCount)
        assertEquals(0, metrics.boxStartCount)
        assertEquals(0, metrics.boxCloseCount)
        assertEquals(0, fakeBridge.startCount.get())
        assertEquals(0, fakeBridge.closeCount.get())
    }

    @Test
    fun batchLifecycleFor1_5_20ProfilesCreatesStartsAndClosesExactlyOneBox() = runBlocking {
        for (count in listOf(1, 5, 20)) {
            val profiles = (1..count).map { socks(it.toLong()) }
            var createCount = 0
            val fakeBridge = FakeBoxBridge { 50 }

            val runner = BatchUrlTestRunner(
                configuredConcurrency = 5,
                boxFactory = {
                    createCount++
                    fakeBridge
                },
            )

            val results = CopyOnWriteArrayList<ProxyEntity>()
            runner.run(profiles) { results.add(it) }

            assertEquals(count, results.size)
            assertEquals(1, createCount)
            assertEquals(1, fakeBridge.startCount.get())
            assertEquals(1, fakeBridge.closeCount.get())

            val metrics = runner.lastMetrics
            assertEquals(1, metrics.boxCreateCount)
            assertEquals(1, metrics.boxStartCount)
            assertEquals(1, metrics.boxCloseCount)
            assertEquals(count, metrics.batchProbeCount)
            assertEquals(0, metrics.fallbackProbeCount)
        }
    }

    @Test
    fun outOfOrderCompletionMappingBindsCorrectly() = runBlocking {
        val p1 = socks(10L)
        val p2 = socks(20L)
        val p3 = socks(30L)

        // Latencies configured so C (30) completes first, then A (10), then B (20)
        val delayMap = mapOf(
            "ut-10-main" to 60L,
            "ut-20-main" to 120L,
            "ut-30-main" to 20L,
        )
        val pingMap = mapOf(
            "ut-10-main" to 100,
            "ut-20-main" to 200,
            "ut-30-main" to 300,
        )

        val completionOrder = CopyOnWriteArrayList<Long>()
        val receivedPings = ConcurrentHashMap<Long, Int>()

        val fakeBridge = FakeBoxBridge { tag ->
            Thread.sleep(delayMap[tag] ?: 10L)
            pingMap[tag] ?: 0
        }

        val runner = BatchUrlTestRunner(
            configuredConcurrency = 5,
            boxFactory = { fakeBridge },
        )

        runner.run(listOf(p1, p2, p3)) { profile ->
            completionOrder.add(profile.id)
            receivedPings[profile.id] = profile.ping
        }

        // Must have completed out of original list order (30 first, then 10, then 20)
        assertEquals(listOf(30L, 10L, 20L), completionOrder.toList())

        // But values must correctly match each profile's tag
        assertEquals(100, receivedPings[10L])
        assertEquals(200, receivedPings[20L])
        assertEquals(300, receivedPings[30L])
    }

    @Test
    fun incrementalResultUpdatesFiredPerNode() = runBlocking {
        val profiles = (1..5).map { socks(it.toLong()) }
        val updateTimes = CopyOnWriteArrayList<Long>()
        val start = System.currentTimeMillis()

        val fakeBridge = FakeBoxBridge {
            Thread.sleep(20)
            50
        }

        val runner = BatchUrlTestRunner(
            configuredConcurrency = 1, // Sequential to clearly observe incremental emission
            boxFactory = { fakeBridge },
        )

        runner.run(profiles) {
            updateTimes.add(System.currentTimeMillis() - start)
        }

        assertEquals(5, updateTimes.size)
        // Each result must arrive incrementally as work finishes
        for (i in 1 until updateTimes.size) {
            assertTrue("Expected update $i to arrive after ${i-1}", updateTimes[i] >= updateTimes[i-1])
        }
    }

    @Test
    fun oneBadNodeFailureIsolationAllowsValidNodesToSucceed() = runBlocking {
        val p1 = socks(1L)
        val bad = ProxyEntity(id = 2L, groupId = 1L).apply {
            putBean(SOCKSBean().apply {
                serverAddress = "" // Blank address triggers validation failure
                serverPort = -1
            })
        }
        val p3 = socks(3L)

        val fakeBridge = FakeBoxBridge { 45 }
        val fallbackProbed = AtomicInteger(0)

        val runner = BatchUrlTestRunner(
            boxFactory = { fakeBridge },
            singleTester = { profile ->
                fallbackProbed.incrementAndGet()
                if (profile.id == 2L) throw IllegalArgumentException("Bad config")
                55
            },
        )

        val results = ConcurrentHashMap<Long, ProxyEntity>()
        runner.run(listOf(p1, bad, p3)) { profile ->
            results[profile.id] = profile
        }

        assertEquals(3, results.size)
        assertEquals(1, results[1L]?.status)
        assertEquals(45, results[1L]?.ping)
        assertEquals(1, results[3L]?.status)
        assertEquals(45, results[3L]?.ping)

        assertEquals(3, results[2L]?.status) // error status
        assertNotNull(results[2L]?.error)

        assertEquals(1, runner.lastMetrics.boxCreateCount)
        assertEquals(2, runner.lastMetrics.batchProbeCount)
        assertEquals(1, runner.lastMetrics.fallbackProbeCount)
    }

    @Test
    fun wholeBatchStartFailureFallsBackAllProfilesToIsolated() = runBlocking {
        val profiles = (1..3).map { socks(it.toLong()) }
        val fallbackTested = CopyOnWriteArrayList<Long>()

        val runner = BatchUrlTestRunner(
            boxFactory = {
                throw RuntimeException("Fatal core initialization failure")
            },
            singleTester = { profile ->
                fallbackTested.add(profile.id)
                60
            },
        )

        val results = CopyOnWriteArrayList<ProxyEntity>()
        runner.run(profiles) { results.add(it) }

        // All 3 profiles successfully tested via fallback escape hatch
        assertEquals(3, results.size)
        assertEquals(listOf(1L, 2L, 3L), fallbackTested.sorted())
        assertTrue(results.all { it.status == 1 && it.ping == 60 })

        // Metrics verify fallback routing
        assertEquals(0, runner.lastMetrics.batchProbeCount)
        assertEquals(3, runner.lastMetrics.fallbackProbeCount)
    }

    @Test
    fun pluginNodeFallbackRoutesToIsolated() = runBlocking {
        val nativeSocks = socks(1L)
        val pluginNode = trojanGoPlugin(2L)

        val fakeBridge = FakeBoxBridge { 35 }
        val fallbackTested = CopyOnWriteArrayList<Long>()

        val runner = BatchUrlTestRunner(
            boxFactory = { fakeBridge },
            singleTester = { profile ->
                fallbackTested.add(profile.id)
                75
            },
        )

        val results = ConcurrentHashMap<Long, ProxyEntity>()
        runner.run(listOf(nativeSocks, pluginNode)) { profile ->
            results[profile.id] = profile
        }

        assertEquals(2, results.size)
        assertEquals(35, results[1L]?.ping)
        assertEquals(75, results[2L]?.ping)
        assertEquals(listOf(2L), fallbackTested.toList())

        assertEquals(1, runner.lastMetrics.batchProbeCount)
        assertEquals(1, runner.lastMetrics.fallbackProbeCount)
    }

    @Test
    fun cancellationClosesBoxAndTerminatesActiveWorkers() = runBlocking {
        val profiles = (1..20).map { socks(it.toLong()) }
        val fakeBridge = FakeBoxBridge {
            Thread.sleep(200)
            50
        }

        val runner = BatchUrlTestRunner(
            configuredConcurrency = 5,
            boxFactory = { fakeBridge },
        )

        val testedCount = AtomicInteger(0)
        val job = launch(Dispatchers.Default) {
            runner.run(profiles) {
                testedCount.incrementAndGet()
            }
        }

        // Allow workers to start then cancel
        delay(50)
        job.cancelAndJoin()

        // Box must be closed
        assertEquals(1, fakeBridge.closeCount.get())
        // Not all 20 nodes could have finished
        assertTrue("Expected fewer than 20 nodes finished due to cancellation", testedCount.get() < 20)
    }

    @Test
    fun concurrencyClamping() {
        val runnerHigh = BatchUrlTestRunner(configuredConcurrency = 25)
        assertEquals(BatchUrlTestRunner.MAX_BATCH_URL_TEST_CONCURRENCY, runnerHigh.effectiveConcurrency)

        val runnerNormal = BatchUrlTestRunner(configuredConcurrency = 5)
        assertEquals(5, runnerNormal.effectiveConcurrency)

        val runnerZero = BatchUrlTestRunner(configuredConcurrency = 0)
        assertEquals(1, runnerZero.effectiveConcurrency)
    }
}
