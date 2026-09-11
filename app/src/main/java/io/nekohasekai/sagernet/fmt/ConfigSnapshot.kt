package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import java.security.MessageDigest

data class ConfigSnapshot(
    val serviceMode: String,
    val allowAccess: Boolean,
    val remoteDns: String,
    val directDns: String,
    val dnsHosts: String,
    val enableDnsRouting: Boolean,
    val enableFakeDns: Boolean,
    val trafficSniffing: Int,
    val ipv6Mode: Int,
    val concurrentDial: Boolean,
    val enableClashAPI: Boolean,
    val logLevel: Int,
    val tunImplementation: Int,
    val mtu: Int,
    val strictRoute: Boolean,
    val mixedPort: Int,
    val mixedInboundHasAuth: Boolean,
    val mixedUsername: String,
    val mixedSecret: String,
    val enableTLSFragment: Boolean,
    val globalMode: Boolean,
    val bypassLan: Boolean,
    val rulesUpdateInterval: String,
    val resolveDestination: Boolean,
    val bypassLanInCore: Boolean,
    val globalCustomConfig: String,
) {
    companion object {
        fun capture(): ConfigSnapshot = ConfigSnapshot(
            serviceMode = DataStore.serviceMode,
            allowAccess = DataStore.allowAccess,
            remoteDns = DataStore.remoteDns,
            directDns = DataStore.directDns,
            dnsHosts = DataStore.dnsHosts,
            enableDnsRouting = DataStore.enableDnsRouting,
            enableFakeDns = DataStore.enableFakeDns,
            trafficSniffing = DataStore.trafficSniffing,
            ipv6Mode = DataStore.ipv6Mode,
            concurrentDial = DataStore.concurrentDial,
            enableClashAPI = DataStore.enableClashAPI,
            logLevel = DataStore.logLevel,
            tunImplementation = DataStore.tunImplementation,
            mtu = DataStore.mtu,
            strictRoute = DataStore.strictRoute,
            mixedPort = DataStore.mixedPort,
            mixedInboundHasAuth = DataStore.mixedInboundHasAuth,
            mixedUsername = DataStore.mixedUsername,
            mixedSecret = DataStore.mixedSecret,
            enableTLSFragment = DataStore.enableTLSFragment,
            globalMode = DataStore.globalMode,
            bypassLan = DataStore.bypassLan,
            rulesUpdateInterval = DataStore.rulesUpdateInterval,
            resolveDestination = DataStore.resolveDestination,
            bypassLanInCore = DataStore.bypassLanInCore,
            globalCustomConfig = DataStore.globalCustomConfig,
        )
    }
}

fun configContentDigest(text: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { b -> "%02x".format(b) }
}
