package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RestoreCoordinator
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val lookupDispatcher = Dispatchers.IO.limitedParallelism(5)
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER
        val reloadBatcher = if (groupId != null) {
            GroupReloadBatcher { System.nanoTime() / 1_000_000L }
        } else {
            null
        }

        try {
            coroutineScope {
                for (profile in profiles) {
                    when (profile) {
                        // SNI rewrite unsupported
                        is NaiveBean -> continue
                    }

                    if (profile.serverAddress.isIpAddress()) continue

                    launch(lookupDispatcher) {
                        try {
                            val underlying = SagerNet.underlyingNetwork
                            val results = if (
                                underlying != null &&
                                DataStore.serviceState.started &&
                                DataStore.serviceMode == Key.MODE_VPN
                            ) {
                                underlying
                                    .getAllByName(profile.serverAddress)
                                    .filterNotNull()
                            } else {
                                InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                            }
                            ensureActive()
                            if (results.isEmpty()) error("empty response")
                            rewriteAddress(profile, results, ipv6First)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.d(e) { "Lookup ${profile.serverAddress} failed: ${e.readableMessage}" }
                        }
                        ensureActive()
                        if (groupId != null) {
                            progress.progress++
                            if (reloadBatcher?.shouldReloadNow() == true) {
                                GroupManager.postReload(groupId)
                            }
                        }
                    }
                }
            }
        } finally {
            // Dispatchers.IO.limitedParallelism does not require closing
        }
        if (groupId != null) {
            GroupManager.postReload(groupId)
            reloadBatcher?.markFlushed()
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }.firstOrNull()?.hostAddress ?: return

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls", "reality" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TrojanGoBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TuicBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is JuicityBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is AnyTLSBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())
        private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            updateScope.launch {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (RestoreCoordinator.isActive()) {
                    Logs.w("Restore is in progress, skipping subscription update for group ${proxyGroup.id}")
                    return@coroutineScope false
                }
                if (!updating.add(proxyGroup.id)) return@coroutineScope false
                val userInterface = GroupManager.userInterface
                var originalGroup: ByteArray? = null
                try {
                    originalGroup = KryoConverters.serialize(proxyGroup)
                    GroupManager.postReload(proxyGroup.id)

                    val subscription = proxyGroup.subscription!!
                    val connected = DataStore.serviceState.connected

                    if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                        if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            return@coroutineScope false
                        }
                    }

                    RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logs.w(e)
                    if (e !is RestoreCoordinator.SubscriptionWriteRejected) {
                        runCatching {
                            RestoreCoordinator.withSubscriptionWrite(SagerNet.application.filesDir) {
                                val current = SagerDatabase.groupDao.getById(proxyGroup.id)
                                if (current != null && originalGroup?.contentEquals(KryoConverters.serialize(current)) == true) {
                                    GroupManager.markRouterRefreshFailed(proxyGroup.id, e.readableMessage)
                                }
                            }
                        }.onFailure { Logs.w(it) }
                    }
                    runCatching { userInterface?.onUpdateFailure(proxyGroup, e.readableMessage) }
                        .onFailure { Logs.w(it) }
                    false
                } finally {
                    withContext(NonCancellable) {
                        runCatching { finishUpdate(proxyGroup) }.onFailure { Logs.w(it) }
                    }
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}
