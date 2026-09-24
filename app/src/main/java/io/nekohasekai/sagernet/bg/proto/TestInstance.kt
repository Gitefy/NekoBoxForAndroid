package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.suspendCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            val phase = AtomicReference("initialize")
            processes = GuardedProcessPool {
                Logs.w {
                    "URLTest isolated process failure profile=${profile.id} type=${profile.type} phase=${phase.get()}: " +
                        (it.localizedMessage?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName)
                }
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                use {
                    try {
                        val initStartedAt = SystemClock.elapsedRealtime()
                        init()
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
                        Logs.i {
                            "URLTest isolated launched profile=${profile.id} type=${profile.type} " +
                                "elapsed=${SystemClock.elapsedRealtime() - launchStartedAt}ms"
                        }
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            delay(500)
                        }
                        phase.set("probe")
                        val probeStartedAt = SystemClock.elapsedRealtime()
                        Logs.i {
                            "URLTest isolated start profile=${profile.id} type=${profile.type} " +
                                "target=${config.connectionTestTargetTag.orEmpty()} timeout=${timeout}ms"
                        }
                        try {
                            val latency = Libcore.urlTestWithTarget(
                                box, link, timeout, config.connectionTestTargetTag.orEmpty()
                            )
                            Logs.i {
                                "URLTest isolated success profile=${profile.id} type=${profile.type} latency=${latency}ms elapsed=${SystemClock.elapsedRealtime() - probeStartedAt}ms"
                            }
                            c.tryResume(latency)
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
                        c.tryResumeWithException(e)
                    }
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d({ config.config })
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
