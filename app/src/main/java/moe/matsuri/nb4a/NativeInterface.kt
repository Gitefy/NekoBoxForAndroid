package moe.matsuri.nb4a

import android.content.Context
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import java.io.IOException
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.routerStableId
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.ConnectionResetDebouncer
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.route.routerNodeKey
import libcore.BoxPlatformInterface
import libcore.NB4AInterface
import java.net.InetSocketAddress

// Capture one selected Network for this socket. Abort a dial if handover races
// with binding, so its caller cannot use a descriptor bound to the old path.
internal fun <T : Any> protectAndBindNetwork(
    current: () -> T?,
    protect: () -> Boolean,
    bind: (T) -> Unit,
) {
    val selected = current() ?: throw IOException("VPN underlying network is not ready")
    if (!protect()) throw IOException("VPN socket protection failed")
    bind(selected)
    if (current() != selected) throw IOException("VPN underlying network changed during socket binding")
}

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        val vpn = DataStore.vpnService
        if (vpn != null) {
            protectAndBindNetwork(
                current = { SagerNet.underlyingNetwork },
                protect = { vpn.protect(fd) },
                bind = { network ->
                    // fromFd duplicates the descriptor. Close only this copy;
                    // the original remains owned by Go or the IPC sender.
                    ParcelFileDescriptor.fromFd(fd).use {
                        network.bindSocket(it.fileDescriptor)
                    }
                },
            )
            return
        }
        val active = SagerNet.connectivity.activeNetwork
        val vpnActive = active?.let { runCatching { SagerNet.connectivity.getNetworkCapabilities(it) }.getOrNull() }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (vpnActive) {
            // The Go UI bridge forwards this fd to the VPN process over the
            // existing protection socket when platform control returns an error.
            throw IOException("VPN socket protection requires the active service process")
        }
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) return packageNames.sorted().joinToString(",")

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        val service = DataStore.baseService
        val proxy = service?.data?.proxy
        val routerTag = proxy?.config?.routerSelectorTags?.entries
            ?.firstOrNull { it.value == selectorTag }?.key
        if (routerTag != null && service != null && proxy != null) {
            val id = proxy.config.profileIdByTag[tag] ?: return
            runOnDefaultDispatcher {
                if (DataStore.baseService !== service || service.data.proxy !== proxy) return@runOnDefaultDispatcher
                val selected = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                SagerDatabase.routerGroupDao.getByStableTag(routerTag)?.let { router ->
                    SagerDatabase.routerGroupDao.updateSelection(
                        routerId = router.id,
                        selectedProxyId = id,
                        selectedNodeKey = routerNodeKey(
                            selected.groupId,
                            selected.routerStableId(),
                        ),
                    )
                }
            }
            return
        }
        if (selectorTag != "proxy") {
            Logs.d({ "other selector: $selectorTag" })
            return
        }
        ConnectionResetDebouncer.resetAllConnections()
        if (service == null || proxy == null) return
        runOnDefaultDispatcher {
            if (DataStore.baseService !== service || service.data.proxy !== proxy) return@runOnDefaultDispatcher
            val id = proxy.config.profileIdByTag[tag] ?: -1
            val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
            // traffic & title
            val title = ServiceNotification.genTitle(ent)
            proxy.apply {
                looper?.selectMain(id)
                displayProfileName = title
            }
            onMainDispatcher {
                if (DataStore.baseService !== service || service.data.proxy !== proxy) return@onMainDispatcher
                service.data.notification?.postNotificationTitle(title)
            }
            // post binder
            service.data.binder.broadcast { b ->
                b.cbSelectorUpdate(id)
            }
        }
    }

}
