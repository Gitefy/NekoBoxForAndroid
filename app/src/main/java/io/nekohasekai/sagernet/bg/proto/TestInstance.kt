package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
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
import kotlin.coroutines.suspendCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                use {
                    try {
                        init()
                        launch()
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            delay(500)
                        }
                        val probeStartedAt = SystemClock.elapsedRealtime()
                        Logs.i {
                            "URLTest isolated start profile=${profile.id} type=${profile.type} timeout=${timeout}ms"
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
