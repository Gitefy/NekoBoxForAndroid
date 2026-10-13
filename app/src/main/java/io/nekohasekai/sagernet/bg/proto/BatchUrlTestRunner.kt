package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.BatchConfigBuilder
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BoxBridge encapsulates the lifecycle and probing operations for a sing-box instance.
 */
interface BoxBridge {
    fun start()
    fun close()
    fun urlTestWithTarget(link: String, timeout: Int, targetTag: String): Int
}

internal class SingBoxBridge(private val box: BoxInstance, private val cacheFile: UrlTestCacheFile) : BoxBridge {
    override fun start() {
        box.start()
    }

    override fun close() {
        try {
            box.close()
        } finally {
            cacheFile.close()
        }
    }

    override fun urlTestWithTarget(link: String, timeout: Int, targetTag: String): Int {
        return Libcore.urlTestWithTarget(box, link, timeout, targetTag)
    }
}

data class BatchMetrics(
    var boxCreateCount: Int = 0,
    var boxStartCount: Int = 0,
    var boxCloseCount: Int = 0,
    var batchProbeCount: Int = 0,
    var fallbackProbeCount: Int = 0,
    var totalElapsedMs: Long = 0,
    var firstResultElapsedMs: Long = 0,
)

/**
 * BatchUrlTestRunner coordinates batch connectivity testing using a single shared
 * BoxInstance for all batch-eligible profiles, with concurrent worker probing up to
 * MAX_BATCH_URL_TEST_CONCURRENCY.
 *
 * Ineligible profiles (plugins, bad configs) or profiles subjected to whole-batch start
 * failure automatically fall back to isolated F1 testing.
 */
class BatchUrlTestRunner(
    val link: String = runCatching { DataStore.connectionTestURL }.getOrDefault("https://www.gstatic.com/generate_204"),
    val timeout: Int = runCatching { DataStore.connectionTestTimeout }.getOrDefault(3000),
    val configuredConcurrency: Int = runCatching { DataStore.connectionTestConcurrent }.getOrDefault(5),
    private val boxFactory: (String) -> BoxBridge? = { configJson ->
        val cacheFile = UrlTestCacheFile.create(SagerNet.application.cacheDir)
        try {
            val box = Libcore.newSingBoxInstance(cacheFile.applyTo(configJson), LocalResolverImpl)
            if (box != null) SingBoxBridge(box, cacheFile) else {
                cacheFile.close()
                null
            }
        } catch (e: Throwable) {
            cacheFile.close()
            throw e
        }
    },
    private val singleTester: suspend (ProxyEntity) -> Int = { profile ->
        TestInstance(profile, link, timeout).doTest()
    },
    // Null means absent from the running core; probe failures must propagate.
    val runtimeTester: (suspend (ProxyEntity) -> Int?)? = null,
    val activeTester: (suspend (ProxyEntity) -> Int)? = null,
    val activeProfileId: Long? = runCatching {
        if (DataStore.serviceState.connected) {
            DataStore.selectedProxy.takeIf { it > 0 } ?: DataStore.currentProfile
        } else null
    }.getOrNull(),
) {

    companion object {
        const val MAX_BATCH_URL_TEST_CONCURRENCY = 10
    }

    var lastMetrics = BatchMetrics()
        private set

    val effectiveConcurrency: Int
        get() = minOf(maxOf(configuredConcurrency, 1), MAX_BATCH_URL_TEST_CONCURRENCY)

    suspend fun run(
        profilesList: List<ProxyEntity>,
        groups: Map<Long, ProxyGroup?> = emptyMap(),
        proxies: Map<Long, ProxyEntity> = emptyMap(),
        directDns: String = "223.5.5.5",
        onResult: (ProxyEntity) -> Unit,
    ) = coroutineScope {
        val startTime = System.currentTimeMillis()
        val metrics = BatchMetrics()
        val firstResultRecorded = AtomicBoolean(false)
        val batchProbeCounter = AtomicInteger(0)
        val fallbackProbeCounter = AtomicInteger(0)
        val probePermits = Semaphore(effectiveConcurrency)

        fun recordResult(profile: ProxyEntity) {
            if (firstResultRecorded.compareAndSet(false, true)) {
                metrics.firstResultElapsedMs = System.currentTimeMillis() - startTime
            }
            onResult(profile)
        }

        if (profilesList.isEmpty()) {
            metrics.totalElapsedMs = System.currentTimeMillis() - startTime
            lastMetrics = metrics
            return@coroutineScope
        }

        val batchResult = BatchConfigBuilder.build(
            profiles = profilesList,
            groups = groups,
            proxies = proxies,
            directDns = directDns,
        )

        val batchEligible = batchResult.batchEligibleProfiles
        val fallbackQueue = ConcurrentLinkedQueue(batchResult.fallbackProfiles)
        val batchQueue = ConcurrentLinkedQueue<ProxyEntity>()

        var batchBridge: BoxBridge? = null
        if (batchEligible.isNotEmpty()) {
            try {
                metrics.boxCreateCount++
                val bridge = boxFactory(batchResult.config)
                    ?: throw IllegalStateException("boxFactory returned null")
                batchBridge = bridge
                bridge.start()
                metrics.boxStartCount++
                batchQueue.addAll(batchEligible)
                Logs.d("BatchUrlTest: started shared BoxInstance for ${batchEligible.size} nodes")
            } catch (e: Exception) {
                Logs.w("BatchUrlTest: shared Box start failed: ${e.readableMsg}; falling back to isolated tests", e)
                runCatching {
                    metrics.boxCloseCount++
                    batchBridge?.close()
                }
                batchBridge = null
                // Whole-batch start failure: all batch-eligible profiles fall back to isolated F1 path
                fallbackQueue.addAll(batchEligible)
            }
        }

        try {
            val workers = mutableListOf<Job>()

            // 1. Process batch-eligible nodes using the shared Box
            if (batchBridge != null) {
                repeat(effectiveConcurrency) {
                    workers.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = batchQueue.poll() ?: break
                            val targetTag = batchResult.targetTagMap[profile.id]
                            if (targetTag.isNullOrBlank()) {
                                fallbackProbeCounter.incrementAndGet()
                                executeIsolated(profile, probePermits, ::recordResult)
                                continue
                            }

                            probePermits.acquire()
                            try {
                                profile.status = 0
                                try {
                                    val ping = if (activeTester != null && activeProfileId != null && profile.id == activeProfileId) {
                                        try {
                                            activeTester(profile)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Logs.w("URLTest: activeTester failed in batch: ${e.readableMsg}; falling back to shared probe", e)
                                            batchBridge.urlTestWithTarget(link, timeout, targetTag)
                                        }
                                    } else {
                                        batchBridge.urlTestWithTarget(link, timeout, targetTag)
                                    }
                                    profile.status = 1
                                    profile.ping = ping
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    profile.status = 3
                                    profile.error = e.readableMsg
                                    Logs.w {
                                        "URLTest batch failed profile=${profile.id} type=${profile.type}: ${e.readableMsg}"
                                    }
                                }
                            } finally {
                                probePermits.release()
                            }
                            batchProbeCounter.incrementAndGet()
                            recordResult(profile)
                        }
                    })
                }
            }

            // 2. Process fallback profiles concurrently with shared Box testing
            if (fallbackQueue.isNotEmpty()) {
                val fallbackConcurrency = if (batchBridge != null) {
                    minOf(effectiveConcurrency, maxOf(1, effectiveConcurrency / 2))
                } else {
                    effectiveConcurrency
                }
                repeat(fallbackConcurrency) {
                    workers.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = fallbackQueue.poll() ?: break
                            fallbackProbeCounter.incrementAndGet()
                            executeIsolated(profile, probePermits, ::recordResult)
                        }
                    })
                }
            }
            workers.joinAll()
        } finally {
            if (batchBridge != null) {
                runCatching {
                    metrics.boxCloseCount++
                    batchBridge.close()
                }.onFailure { Logs.w(it) }
                Logs.d("BatchUrlTest: closed shared BoxInstance")
            }
            metrics.batchProbeCount = batchProbeCounter.get()
            metrics.fallbackProbeCount = fallbackProbeCounter.get()
            metrics.totalElapsedMs = System.currentTimeMillis() - startTime
            lastMetrics = metrics
        }
    }

    private suspend fun executeIsolated(profile: ProxyEntity, permits: Semaphore, onResult: (ProxyEntity) -> Unit) {
        profile.status = 0
        permits.acquire()
        try {
            try {
                val ping = if (runtimeTester != null) {
                    runtimeTester.invoke(profile) ?: singleTester(profile)
                } else if (activeTester != null && activeProfileId != null && profile.id == activeProfileId) {
                    try {
                        activeTester(profile)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w("URLTest: activeTester failed for active profile ${profile.id}: ${e.readableMsg}", e)
                        throw e
                    }
                } else {
                    singleTester(profile)
                }
                profile.status = 1
                profile.ping = ping
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.javaClass.simpleName == "PluginNotFoundException") {
                    profile.status = 2
                } else {
                    profile.status = 3
                }
                profile.error = e.readableMsg
                Logs.w {
                    "URLTest isolated failed profile=${profile.id} type=${profile.type}: ${e.readableMsg}"
                }
            }
        } finally {
            permits.release()
        }
        onResult(profile)
    }
}

private val Throwable.readableMsg: String
    get() = localizedMessage.takeIf { !it.isNullOrBlank() } ?: javaClass.simpleName
