package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation

internal fun needsPostLaunchDelay(processCount: Int): Boolean = processCount > 0

class TestInstance(profile: ProxyEntity, val link: String, internal val timeout: Int) :
    BoxInstance(profile) {

    private var cacheFile: UrlTestCacheFile? = null

    suspend fun doTest(): Int = coroutineScope {
        suspendCancellableCoroutine { c ->
            val continuation: Continuation<Int> = c
            val phase = AtomicReference("initialize")
            val worker = launch(Dispatchers.Default) {
                processes = GuardedProcessPool {
                    Logs.w {
                        "URLTest isolated process failure profile=${profile.id} type=${profile.type} phase=${phase.get()}: " +
                            (it.localizedMessage?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName)
                    }
                    continuation.tryResumeWithException(it)
                }
                use {
                    try {
                        ensureActive()
                        val initStartedAt = SystemClock.elapsedRealtime()
                        init()
                        ensureActive()
                        val cacheFileEnabled = runCatching {
                            JsonParser.parseString(config.config)
                                .asJsonObject.getAsJsonObject("experimental")
                                ?.getAsJsonObject("cache_file")
                                ?.get("enabled")
                                ?.asBoolean
                                ?.toString()
                                ?: "omitted"
                        }.getOrDefault("unreadable")
                        Logs.i {
                            "URLTest isolated initialized profile=${profile.id} type=${profile.type} " +
                                "target=${config.connectionTestTargetTag.orEmpty()} cache_file_enabled=$cacheFileEnabled " +
                                "elapsed=${SystemClock.elapsedRealtime() - initStartedAt}ms"
                        }

                        phase.set("launch")
                        val launchStartedAt = SystemClock.elapsedRealtime()
                        launch()
                        ensureActive()
                        Logs.i {
                            "URLTest isolated launched profile=${profile.id} type=${profile.type} " +
                                "elapsed=${SystemClock.elapsedRealtime() - launchStartedAt}ms"
                        }
                        if (needsPostLaunchDelay(processes.processCount)) {
                            // wait for plugin start
                            delay(500)
                        }
                        ensureActive()
                        phase.set("probe")
                        val probeStartedAt = SystemClock.elapsedRealtime()
                        Logs.i {
                            "URLTest isolated start profile=${profile.id} type=${profile.type} " +
                                "target=${config.connectionTestTargetTag.orEmpty()} timeout=${timeout}ms " +
                                "serviceState=${DataStore.serviceState} vpnService=${DataStore.vpnService != null} " +
                                "isBg=${SagerNet.application.isBgProcess}"
                        }
                        try {
                            val latency = Libcore.urlTestWithTarget(
                                box, link, timeout, config.connectionTestTargetTag.orEmpty()
                            )
                            Logs.i {
                                "URLTest isolated success profile=${profile.id} type=${profile.type} latency=${latency}ms elapsed=${SystemClock.elapsedRealtime() - probeStartedAt}ms"
                            }
                            continuation.tryResume(latency)
                        } catch (e: Exception) {
                            Logs.w {
                                "URLTest isolated failed profile=${profile.id} type=${profile.type} elapsed=${SystemClock.elapsedRealtime() - probeStartedAt}ms: ${e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
                            }
                            throw e
                        }
                    } catch (e: Exception) {
                        if (phase.get() != "probe") {
                            Logs.w {
                                "URLTest isolated setup failed profile=${profile.id} type=${profile.type} " +
                                    "phase=${phase.get()}: ${e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
                            }
                        }
                        continuation.tryResumeWithException(e)
                    }
                }
            }
            c.invokeOnCancellation { worker.cancel() }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        val temporaryCache = UrlTestCacheFile.create(SagerNet.application.cacheDir)
        cacheFile = temporaryCache
        box = Libcore.newSingBoxInstance(temporaryCache.applyTo(config.config), LocalResolverImpl)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            cacheFile?.close()
            cacheFile = null
        }
    }
}
