package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterMember
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
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
    val domainStrategyRemote: String,
    val domainStrategyDirect: String,
    val domainStrategyServer: String,
) {
    fun domainStrategy(tag: String): String {
        fun auto2(raw: String, newS: String) = raw.replace("auto", newS)
        return when (tag) {
            "dns-remote" -> auto2(domainStrategyRemote, "")
            "dns-direct" -> auto2(domainStrategyDirect, "")
            else -> auto2(domainStrategyServer, "prefer_ipv4")
        }
    }

    companion object {
        fun fromFrozenRows(
            rows: List<KeyValuePair>,
            mixedPortFallback: Int = 2080,
        ): ConfigSnapshot {
            val byKey = HashMap<String, KeyValuePair>(rows.size)
            for (row in rows) byKey[row.key] = row
            fun str(key: String, default: String): String = byKey[key]?.string ?: default
            fun bool(key: String, default: Boolean = false): Boolean = byKey[key]?.boolean ?: default
            fun stringToInt(key: String, default: Int): Int =
                byKey[key]?.string?.toIntOrNull() ?: default
            fun port(raw: String?, fallback: Int): Int {
                val value = raw?.toIntOrNull() ?: fallback
                return if (value < 1025 || value > 65535) fallback else value
            }
            val mixedUsername = str(Key.MIXED_USERNAME_PREF, Key.MIXED_USERNAME)
            val mixedSecret = str(Key.MIXED_SECRET, "")
            val serviceMode = str(Key.SERVICE_MODE, Key.MODE_VPN)
            return ConfigSnapshot(
                serviceMode = serviceMode,
                allowAccess = bool(Key.ALLOW_ACCESS),
                remoteDns = str(Key.REMOTE_DNS, "https://dns.google/dns-query"),
                directDns = str(Key.DIRECT_DNS, "https://223.5.5.5/dns-query"),
                dnsHosts = str(Key.DNS_HOSTS, ""),
                enableDnsRouting = bool(Key.ENABLE_DNS_ROUTING, true),
                enableFakeDns = bool(Key.ENABLE_FAKEDNS, true),
                trafficSniffing = stringToInt(Key.TRAFFIC_SNIFFING, 1),
                ipv6Mode = stringToInt(Key.IPV6_MODE, IPv6Mode.DISABLE),
                concurrentDial = bool(Key.CONCURRENT_DIAL),
                enableClashAPI = bool(Key.ENABLE_CLASH_API),
                logLevel = stringToInt(Key.LOG_LEVEL, 0),
                tunImplementation = stringToInt(Key.TUN_IMPLEMENTATION, TunImplementation.GO),
                mtu = stringToInt(Key.MTU, 9000),
                strictRoute = bool(Key.STRICT_ROUTE, true),
                mixedPort = port(byKey[Key.MIXED_PORT]?.string, mixedPortFallback),
                mixedInboundHasAuth = serviceMode == Key.MODE_VPN &&
                    (mixedUsername.isNotEmpty() || mixedSecret.isNotEmpty()),
                mixedUsername = mixedUsername,
                mixedSecret = mixedSecret,
                enableTLSFragment = false,
                globalMode = bool(Key.GLOBAL_MODE),
                bypassLan = bool(Key.BYPASS_LAN),
                rulesUpdateInterval = str(Key.RULES_UPDATE_INTERVAL, "0"),
                resolveDestination = bool(Key.RESOLVE_DESTINATION),
                bypassLanInCore = bool(Key.BYPASS_LAN_IN_CORE),
                globalCustomConfig = str(Key.GLOBAL_CUSTOM_CONFIG, ""),
                domainStrategyRemote = str("domain_strategy_for_remote", ""),
                domainStrategyDirect = str("domain_strategy_for_direct", ""),
                domainStrategyServer = str("domain_strategy_for_server", ""),
            )
        }
    }
}

data class CapturedConfig(
    val proxy: ProxyEntity,
    val forTest: Boolean,
    val forExport: Boolean,
    val settings: ConfigSnapshot,
    val groups: Map<Long, ProxyGroup?>,
    val proxies: Map<Long, ProxyEntity>,
    val proxiesByGroup: Map<Long, List<ProxyEntity>>,
    val extraRules: List<RuleEntity>,
    val routerGroups: List<RouterGroup>,
    val routerMembers: Map<Long, List<RouterMember>>,
)

fun configContentDigest(text: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { b -> "%02x".format(b) }
}
