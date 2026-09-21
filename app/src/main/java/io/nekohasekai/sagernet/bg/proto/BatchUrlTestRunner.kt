package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.BatchConfigBuilder
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BatchUrlTestRunner coordinates batch connectivity testing using a single shared
 * BoxInstance for all batch-eligible profiles, with concurrent worker probing up to
 * MAX_BATCH_URL_TEST_CONCURRENCY.
 *
 * Ineligible profiles (plugins, bad configs) or profiles subjected to whole-batch start
 * failure automatically fall back to isolated F1 testing.
 */
class BatchUrlTestRunner(
    val link: String = DataStore.connectionTestURL,
    val timeout: Int = DataStore.connectionTestTimeout,
    val configuredConcurrency: Int = DataStore.connectionTestConcurrent,
    private val boxFactory: (String) -> BoxInstance? = { configJson ->
        Libcore.newSingBoxInstance(configJson, LocalResolverImpl)
    },
    private val singleTester: suspend (ProxyEntity) -> Int = { profile ->
        TestInstance(profile, link, timeout).doTest()
    },
) {

    companion object {
        const val MAX_BATCH_URL_TEST_CONCURRENCY = 10
    }

    val effectiveConcurrency: Int
        get() = minOf(maxOf(configuredConcurrency, 1), MAX_BATCH_URL_TEST_CONCURRENCY)

    suspend fun run(
        profilesList: List<ProxyEntity>,
        groups: Map<Long, ProxyGroup?> = emptyMap(),
        proxies: Map<Long, ProxyEntity> = emptyMap(),
        directDns: String = "223.5.5.5",
        onResult: (ProxyEntity) -> Unit,
    ) = coroutineScope {
        if (profilesList.isEmpty()) return@coroutineScope

        val batchResult = BatchConfigBuilder.build(
            profiles = profilesList,
            groups = groups,
            proxies = proxies,
            directDns = directDns,
        )

        val batchEligible = batchResult.batchEligibleProfiles
        val fallbackQueue = ConcurrentLinkedQueue(batchResult.fallbackProfiles)
        val batchQueue = ConcurrentLinkedQueue<ProxyEntity>()

        var batchBox: BoxInstance? = null
        if (batchEligible.isNotEmpty()) {
            try {
                val box = boxFactory(batchResult.config)
                    ?: throw IllegalStateException("boxFactory returned null")
                box.start()
                batchBox = box
                batchQueue.addAll(batchEligible)
                Logs.d("BatchUrlTest: started shared BoxInstance for ${batchEligible.size} nodes")
            } catch (e: Exception) {
                Logs.w("BatchUrlTest: shared Box start failed: ${e.readableMessage}; falling back to isolated tests", e)
                runCatching { batchBox?.close() }
                batchBox = null
                // Whole-batch start failure: all batch-eligible profiles fall back to isolated F1 path
                fallbackQueue.addAll(batchEligible)
            }
        }

        try {
            val workers = mutableListOf<Job>()

            // 1. Process batch-eligible nodes using the shared Box
            if (batchBox != null) {
                repeat(effectiveConcurrency) {
                    workers.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = batchQueue.poll() ?: break
                            val targetTag = batchResult.targetTagMap[profile.id]
                            if (targetTag.isNullOrBlank()) {
                                executeIsolated(profile, onResult)
                                continue
                            }

                            profile.status = 0
                            try {
                                val ping = Libcore.urlTestWithTarget(batchBox, link, timeout, targetTag)
                                profile.status = 1
                                profile.ping = ping
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.readableMessage
                            }
                            onResult(profile)
                        }
                    })
                }
                workers.joinAll()
                workers.clear()
            }

            // 2. Process fallback profiles using isolated test
            if (fallbackQueue.isNotEmpty()) {
                repeat(effectiveConcurrency) {
                    workers.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = fallbackQueue.poll() ?: break
                            executeIsolated(profile, onResult)
                        }
                    })
                }
                workers.joinAll()
            }
        } finally {
            if (batchBox != null) {
                runCatching { batchBox.close() }.onFailure { Logs.w(it) }
                Logs.d("BatchUrlTest: closed shared BoxInstance")
            }
        }
    }

    private suspend fun executeIsolated(profile: ProxyEntity, onResult: (ProxyEntity) -> Unit) {
        profile.status = 0
        try {
            val ping = singleTester(profile)
            profile.status = 1
            profile.ping = ping
        } catch (e: PluginManager.PluginNotFoundException) {
            profile.status = 2
            profile.error = e.readableMessage
        } catch (e: Exception) {
            profile.status = 3
            profile.error = e.readableMessage
        }
        onResult(profile)
    }
}
