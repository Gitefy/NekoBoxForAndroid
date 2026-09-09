package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    override var wakeLock: PowerManager.WakeLock? = null

    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L

        internal fun tunAddresses(ipv6Mode: Int): List<TunAddress> = when (ipv6Mode) {
            IPv6Mode.DISABLE -> listOf(TunAddress(PRIVATE_VLAN4_CLIENT, 30))
            IPv6Mode.ONLY -> listOf(TunAddress(PRIVATE_VLAN6_CLIENT, 126))
            else -> listOf(
                TunAddress(PRIVATE_VLAN4_CLIENT, 30),
                TunAddress(PRIVATE_VLAN6_CLIENT, 126),
            )
        }

        internal fun tunDnsServers(addresses: List<TunAddress>): List<String> = buildList {
            if (addresses.any { ':' !in it.host }) add(PRIVATE_VLAN4_ROUTER)
            if (addresses.any { ':' in it.host }) add(PRIVATE_VLAN6_ROUTER)
        }

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun killProcesses() {
        conn?.close()
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, ServiceNotification.vpnNotificationChannel)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
        val fallbackAddresses = tunAddresses(DataStore.ipv6Mode)
        return establishTun(tunPlatformConfig(
            tunOptionsJson,
            DataStore.mtu,
            fallbackAddresses,
            tunDnsServers(fallbackAddresses),
        ))
    }

    internal fun establishTun(platformConfig: TunPlatformConfig): Int {
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(platformConfig.mtu)
        val hasIpv4 = platformConfig.addresses.any { ':' !in it.host }
        val hasIpv6 = platformConfig.addresses.any { ':' in it.host }

        platformConfig.addresses.forEach { builder.addAddress(it.host, it.prefixLength) }
        platformConfig.dnsServers.forEach(builder::addDnsServer)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            if (hasIpv4) {
                builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
                builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            }
            // https://issuetracker.google.com/issues/149636790
            if (hasIpv6) {
                builder.addRoute("2000::", 3)
            }
        } else {
            if (hasIpv4) builder.addRoute("0.0.0.0", 0)
            if (hasIpv6) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass
        val needBypassRootUid = data.proxy!!.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Allow Matsuri itself using VPN.
                remove(packageName)
                if (!bypass) add(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d({ "Add bypass: ${added.joinToString(", ")}" })
            } else {
                Logs.d({ "Add allow: ${added.joinToString(", ")}" })
            }
        }

        metered = DataStore.meteredNetwork
        builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        SagerNet.underlyingNetwork?.let {
            builder?.setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
                ?: setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
