package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import libcore.Libcore
import java.net.NetworkInterface
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class CancelGet(val request: Get) : NetworkMessage()

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Properties(val network: Network, val interfaceName: String?) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    @Volatile var interfaceName: String? = null
        private set

    fun findUnderlyingNetwork(): Network? {
        val cm = runCatching { SagerNet.connectivity }.getOrNull() ?: return null
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (active != null) {
            val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return active
            }
        }
        return runCatching {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrNull()
    }

    // Dedicated scope on Default: the old GlobalScope.actor(Unconfined) inherited
    // the sender's thread, so heavy listener work (getLinkProperties, connection
    // resets, urltest refresh) could land on the main thread or the system
    // ConnectivityThread. Actor confinement keeps all state transitions on one
    // background thread and never borrows the caller's.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)
    private val networkActor = scope.actor<NetworkMessage>(capacity = Channel.UNLIMITED) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        var candidateInterfaceName: String? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()

        fun seedIfEmpty() {
            if (network == null) {
                val seed = findUnderlyingNetwork()
                if (seed != null) {
                    network = seed
                    candidateInterfaceName = runCatching {
                        SagerNet.connectivity.getLinkProperties(seed)?.interfaceName
                    }.getOrNull()
                    publishInterface(seed, candidateInterfaceName)
                }
            }
        }

        for (message in channel) {
            try {
                when (message) {
                    is NetworkMessage.Start -> {
                        if (listeners.isEmpty()) {
                            runCatching { register() }.onFailure { Logs.e("Failed to register network callback", it) }
                        }
                        listeners[message.key] = message.listener
                        seedIfEmpty()
                        if (SagerNet.underlyingNetwork != null) {
                            runCatching { message.listener(SagerNet.underlyingNetwork) }.onFailure { Logs.w(it) }
                        }
                    }
                    is NetworkMessage.Get -> {
                        if (listeners.isEmpty()) {
                            message.response.completeExceptionally(IllegalStateException("Getting network without any listeners is not supported"))
                        } else {
                            seedIfEmpty()
                            if (network != null && SagerNet.underlyingNetwork == network) {
                                message.response.complete(network)
                            } else {
                                pendingRequests += message
                            }
                        }
                    }
                    is NetworkMessage.CancelGet -> pendingRequests.remove(message.request)
                    is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                        listeners.remove(message.key) != null && listeners.isEmpty()
                    ) {
                        network = null
                        candidateInterfaceName = null
                        runCatching { unregister() }.onFailure { Logs.w(it) }
                    }

                    is NetworkMessage.Put -> if (network != message.network) {
                        network = message.network
                        candidateInterfaceName = runCatching {
                            SagerNet.connectivity.getLinkProperties(message.network)?.interfaceName
                        }.getOrNull()
                        if (publishInterface(network, candidateInterfaceName)) {
                            pendingRequests.forEach { it.response.complete(message.network) }
                            pendingRequests.clear()
                        }
                        listeners.values.forEach { listener ->
                            runCatching { listener(SagerNet.underlyingNetwork) }.onFailure { Logs.w(it) }
                        }
                    }
                    is NetworkMessage.Properties -> if (network == message.network) {
                        candidateInterfaceName = message.interfaceName
                        if (publishInterface(network, candidateInterfaceName)) {
                            pendingRequests.forEach { it.response.complete(message.network) }
                            pendingRequests.clear()
                        }
                        listeners.values.forEach { listener ->
                            runCatching { listener(SagerNet.underlyingNetwork) }.onFailure { Logs.w(it) }
                        }
                    }
                    is NetworkMessage.Update -> if (network == message.network) {
                        if (publishInterface(network, candidateInterfaceName)) {
                            pendingRequests.forEach { it.response.complete(message.network) }
                            pendingRequests.clear()
                        }
                        listeners.values.forEach { listener ->
                            runCatching { listener(SagerNet.underlyingNetwork) }.onFailure { Logs.w(it) }
                        }
                    }
                    is NetworkMessage.Lost -> if (network == message.network) {
                        network = null
                        candidateInterfaceName = null
                        publishInterface(null, null)
                        listeners.values.forEach { listener ->
                            runCatching { listener(null) }.onFailure { Logs.w(it) }
                        }
                    }
                }
            } catch (e: Throwable) {
                Logs.e("Error processing network actor message", e)
            }
        }
    }

    // Every core in this process, including isolated URL tests, shares this monitor.
    private fun publishInterface(network: Network?, name: String?): Boolean {
        return runCatching {
            val resolvedName = name?.takeIf { it.isNotEmpty() }
                ?: network?.let { runCatching { SagerNet.connectivity.getLinkProperties(it)?.interfaceName }.getOrNull() }?.takeIf { it.isNotEmpty() }
            val index = resolvedName?.let { ifName ->
                runCatching { android.system.Os.if_nametoindex(ifName) }.getOrNull()?.takeIf { it > 0 }
                    ?: runCatching { NetworkInterface.getByName(ifName)?.index }.getOrNull()?.takeIf { it > 0 }
            } ?: 0
            val ready = network != null && !resolvedName.isNullOrEmpty() && index > 0
            // Publish Java selection before native callbacks can wake and dial.
            SagerNet.underlyingNetwork = network.takeIf { ready }
            interfaceName = resolvedName.takeIf { ready }
            if (network == null || ready) {
                Libcore.updateDefaultInterface(
                    interfaceName.orEmpty(), if (ready) index.toLong() else 0L,
                    DataStore.networkChangeResetConnections,
                )
            }
            ready
        }.getOrElse { error ->
            Logs.w("publishInterface failed", error)
            false
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get() = NetworkMessage.Get().run {
        networkActor.send(this)
        try {
            response.await()
        } finally {
            networkActor.trySend(NetworkMessage.CancelGet(this))
        }
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread — never block it. trySend never
    // suspends; the actor's UNLIMITED buffer absorbs bursts (capability flaps)
    // and the actor drains them on Dispatchers.Default.
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkActor.trySend(NetworkMessage.Put(network))
        }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            networkActor.trySend(NetworkMessage.Update(network))
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networkActor.trySend(NetworkMessage.Properties(network, linkProperties.interfaceName))
        }

        override fun onLost(network: Network) {
            networkActor.trySend(NetworkMessage.Lost(network))
        }
    }

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            SagerNet.connectivity.registerBestMatchingNetworkCallback(request, Callback, mainHandler)
        } catch (e: Exception) {
            try {
                SagerNet.connectivity.registerNetworkCallback(request, Callback, mainHandler)
            } catch (ex: Exception) {
                Logs.e("registerNetworkCallback failed", ex)
            }
        }
    }

    private fun unregister() = runCatching { SagerNet.connectivity.unregisterNetworkCallback(Callback) }.onFailure { Logs.w(it) }
}
