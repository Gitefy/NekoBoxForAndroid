package io.nekohasekai.sagernet.fmt

import android.os.Binder
import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxWireGuardEndpointBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.route.RouterRuntime
import io.nekohasekai.sagernet.route.RouterFilterConfig
import io.nekohasekai.sagernet.route.RouterRuntimeGroup
import io.nekohasekai.sagernet.route.RouterRuntimeMode
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

private val routerSystemReservedTags = setOf(
    TAG_DIRECT,
    TAG_BYPASS,
    TAG_BLOCK,
    TAG_PROXY,
    TAG_FRAGMENT,
    TAG_MIXED,
    TAG_DNS_HOSTS
)

internal fun resolveRouteOutbound(
    rule: RuleEntity,
    mainProxyTag: String,
    proxyTags: Map<Long, String>,
    routerTagsById: Map<Long, String>,
    primaryProxyId: Long = Long.MIN_VALUE
): String {
    // Prefer a built Router outbound when present. Missing, disabled, or empty Router
    // groups intentionally fall back to the rule's legacy outbound so VPN startup is
    // not blocked by temporarily unavailable strategy groups.
    if (rule.routerGroupId > 0L) {
        routerTagsById[rule.routerGroupId]?.let { return it }
    }
    return when (val outId = rule.outbound) {
        0L -> mainProxyTag
        -1L -> TAG_BYPASS
        -2L -> TAG_BLOCK
        else -> if (outId == primaryProxyId) mainProxyTag else proxyTags[outId] ?: ""
    }
}

internal fun routerReservedTags(outbounds: Iterable<SingBoxOption>): Set<String> =
    outbounds.flatMap { outbound ->
        listOfNotNull(outbound.asMap()["tag"] as? String, (outbound as? Outbound)?.tag)
    }.toSet() + routerSystemReservedTags

internal fun buildRouterOutbounds(
    groups: Iterable<RouterRuntimeGroup>,
    proxyTags: Map<Long, String>,
    reservedTags: Set<String> = emptySet(),
    includeRouterGroups: Boolean = true
): List<Outbound> {
    if (!includeRouterGroups) return emptyList()

    return RouterRuntime.build(groups, proxyTags, reservedTags).map { router ->
        when (router.mode) {
            RouterRuntimeMode.SELECTOR -> Outbound_SelectorOptions().apply {
                type = "selector"
                tag = router.tag
                outbounds = router.outbounds
                default_ = router.defaultTag
            }

            RouterRuntimeMode.URL_TEST -> Outbound_URLTestOptions().apply {
                type = "urltest"
                tag = router.tag
                outbounds = router.outbounds
                url = router.filter.testUrl
                interval = "${router.filter.intervalSeconds}s"
                tolerance = router.filter.toleranceMs
                // sing-box enforces interval <= idle_timeout. The core's default idle_timeout is
                // 1800s (30 min). When the user sets a longer interval for battery savings, we
                // must emit a matching idle_timeout so the config can start. We use 2× the
                // interval to give the group enough time to go idle between test rounds.
                if (router.filter.intervalSeconds > 1800) {
                    idle_timeout = "${router.filter.intervalSeconds * 2}s"
                }
            }
        }
    }
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val routerSelectorTags: Map<String, String> = emptyMap(),
    val routerMemberIds: Map<String, Set<Long>> = emptyMap(),
    val routerUrlTestTags: Map<Long, String> = emptyMap(),
    val mainUrlTestTag: String? = null,
    /** Union of all proxy IDs belonging to any Router group (selector or urltest). Used by
     *  TrafficLooper to avoid suppressing independent Router node traffic statistics. */
    val routerAllMemberIds: Set<Long> = emptySet(),
    val connectionTestTargetTag: String? = null,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

private val dnsHostsWhitespaceRegex = "\\s+".toRegex()

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split(dnsHostsWhitespaceRegex)
        if (tokens.size < 2) return@forEach
        val domain = tokens.first()
        val addresses = tokens.drop(1).filter { it.isIpAddress() }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

// serverHostOf parses custom ConfigBean JSON on every call; buildConfig invokes
// it per hop per profile, so identical beans re-parse repeatedly. Cache by bean
// content hash; ConfigBean.config is immutable per entity load.
private val serverHostCache = object : LinkedHashMap<String, String?>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
        return size > 512
    }
}
private val serverHostCacheLock = Any()

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        val cacheKey = configContentDigest(bean.config)
        synchronized(serverHostCacheLock) {
            if (serverHostCache.containsKey(cacheKey)) return serverHostCache[cacheKey]
        }
        val parsed = try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
        synchronized(serverHostCacheLock) {
            serverHostCache[cacheKey] = parsed
        }
        return parsed
    }
    return fallback
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L
            )
        }
    }

    return compileConfig(captureConfigSnapshot(proxy, forTest, forExport))
}

fun captureConfigSnapshot(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): CapturedConfig {
    val settings = ConfigSnapshot.fromFrozenRows(
        DataStore.configurationStore.cachedAll(),
        mixedPortFallback = 2080 + Binder.getCallingUserHandle().hashCode(),
    )
    lateinit var captured: CapturedConfig
    SagerDatabase.instance.runInTransaction {
        val groups = HashMap<Long, ProxyGroup?>()
        val proxies = LinkedHashMap<Long, ProxyEntity>()
        val proxiesByGroup = HashMap<Long, List<ProxyEntity>>()

        fun loadGroup(id: Long): ProxyGroup? {
            if (id <= 0L) return null
            if (groups.containsKey(id)) return groups[id]
            val group = SagerDatabase.groupDao.getById(id)
            groups[id] = group
            return group
        }

        fun loadProxy(id: Long): ProxyEntity? {
            if (id <= 0L) return null
            proxies[id]?.let { return it }
            val entity = SagerDatabase.proxyDao.getById(id) ?: return null
            proxies[entity.id] = entity
            return entity
        }

        fun loadEntities(ids: List<Long>): List<ProxyEntity> {
            val missing = ids.filter { it > 0L && it !in proxies }
            if (missing.isNotEmpty()) {
                for (entity in SagerDatabase.proxyDao.getEntities(missing)) {
                    proxies[entity.id] = entity
                }
            }
            return ids.mapNotNull { proxies[it] }
        }

        val root = loadProxy(proxy.id) ?: proxy.also { proxies[it.id] = it }
        loadGroup(root.groupId)

        fun walk(entity: ProxyEntity, visited: MutableSet<Long>) {
            if (!visited.add(entity.id)) return
            val group = loadGroup(entity.groupId)
            group?.frontProxy?.takeIf { it > 0L }?.let { loadProxy(it)?.let { hop -> walk(hop, visited) } }
            group?.landingProxy?.takeIf { it > 0L }?.let { loadProxy(it)?.let { hop -> walk(hop, visited) } }
            val bean = entity.requireBean()
            if (bean is ChainBean) {
                loadEntities(bean.proxies).forEach { hop -> walk(hop, visited) }
            }
        }

        val visited = HashSet<Long>()
        walk(root, visited)
        val rootGroup = groups[root.groupId]
        if (!forTest && rootGroup?.isSelector == true && !forExport) {
            val list = SagerDatabase.proxyDao.getByGroup(rootGroup.id)
            proxiesByGroup[rootGroup.id] = list
            list.forEach { member ->
                proxies[member.id] = member
                walk(member, visited)
            }
        }

        val extraRules = if (forTest) emptyList() else SagerDatabase.rulesDao.enabledRules()
        val includeRouterGroups = !forTest && !forExport
        val allRouterGroups = if (!includeRouterGroups) emptyList() else SagerDatabase.routerGroupDao.all()
        val enabledRouters = allRouterGroups.filter { it.enabled && it.stableTag.isNotBlank() }
        val routerMembers = if (!includeRouterGroups) {
            emptyMap()
        } else {
            enabledRouters.associate { router ->
                router.id to SagerDatabase.routerMemberDao.getByRouter(router.id)
            }
        }
        val extraProxyIds = extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != root.id }
        }.toMutableSet().apply {
            addAll(routerMembers.values.flatten().map { it.proxyId }.filter { it != root.id })
        }
        loadEntities(extraProxyIds.toList()).forEach { hop -> walk(hop, visited) }
        proxies.values.toList().forEach { loadGroup(it.groupId) }

        captured = CapturedConfig(
            proxy = root,
            forTest = forTest,
            forExport = forExport,
            settings = settings,
            groups = groups.toMap(),
            proxies = proxies.toMap(),
            proxiesByGroup = proxiesByGroup.mapValues { it.value.toList() },
            extraRules = extraRules.toList(),
            routerGroups = allRouterGroups.toList(),
            routerMembers = routerMembers.mapValues { it.value.toList() },
        )
    }
    return captured
}

fun compileConfig(captured: CapturedConfig): ConfigBuildResult {
    val proxy = captured.proxy
    val forTest = captured.forTest
    val forExport = captured.forExport
    val snap = captured.settings
    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = captured.groups[proxy.groupId]

    fun ProxyEntity.resolveChainInternal(visited: MutableSet<Long> = HashSet()): MutableList<ProxyEntity> {
        if (!visited.add(id)) {
            Logs.w({ "Detected cyclic proxy chain involving proxy $id" })
            return mutableListOf()
        }
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = bean.proxies.mapNotNull { captured.proxies[it] }
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal(visited))
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = captured.groups[groupId]
        val frontProxy = thisGroup?.frontProxy?.let { captured.proxies[it] }
        val landingProxy = thisGroup?.landingProxy?.let { captured.proxies[it] }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else captured.extraRules
    val includeRouterGroups = !forTest && !forExport
    val allRouterGroups = if (!includeRouterGroups) {
        listOf()
    } else {
        captured.routerGroups
    }
    val routerGroups = allRouterGroups.filter { it.enabled && it.stableTag.isNotBlank() }
    val routerMembers = if (!includeRouterGroups) {
        mapOf()
    } else {
        captured.routerMembers
    }
    val extraProxyIds = extraRules.mapNotNull { rule ->
        rule.outbound.takeIf { it > 0 && it != proxy.id }
    }.toMutableSet().apply {
        addAll(routerMembers.values.flatten().map { it.proxyId }.filter { it != proxy.id })
    }
    val extraProxies =
        if (forTest) mapOf() else extraProxyIds.mapNotNull { captured.proxies[it] }.associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = snap.serviceMode == Key.MODE_VPN
    val deviceInboundTag = if (isVPN) "tun-in" else TAG_MIXED
    val bind = if (!forTest && snap.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = snap.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = snap.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts by lazy { parseDnsHosts(snap.dnsHosts) }
    val enableDnsRouting = snap.enableDnsRouting
    val useFakeDns = snap.enableFakeDns && !forTest
    val needSniff = snap.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else snap.ipv6Mode
    val dialerFallbackDelay = if (snap.concurrentDial) "300ms" else "900ms"
    val dialerConnectTimeout = "5s"
    fun SingBoxOption.applyDialerTuning() {
        if (forTest) return
        if (this is Outbound_SelectorOptions || this is Outbound_URLTestOptions) return
        _hack_config_map["fallback_delay"] = dialerFallbackDelay
        _hack_config_map["connect_timeout"] = dialerConnectTimeout
    }

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    var routerSelectorTags: Map<String, String> = emptyMap()
    var routerMemberIds: Map<String, Set<Long>> = emptyMap()
    var routerUrlTestTags: Map<Long, String> = emptyMap()
    // Main outbound is always selector or a single chain (never urltest). Router urltest
    // winners are tracked via routerUrlTestTags / TrafficLooper selections instead.
    var mainUrlTestTag: String? = null
    var connectionTestTargetTag: String? = null

    return MyOptions().apply {
	if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    // if (snap.enableClashAPI) {
                    store_fakeip = true
                    // }
                }
                
                if (snap.enableClashAPI) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        external_ui = "../files/yacd"
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (snap.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        // sing-box 1.15 removed the per-DNS-server "strategy" field, and the
        // per-rule "strategy" action option is rejected at startup when combined
        // with "query_type" rules (used by fakeip). The query strategy is now
        // expressed once as the top-level dns.strategy client option; distinct
        // per-target strategies from settings can no longer be represented.
        val dnsStrategy = autoDnsDomainStrategy(snap.domainStrategy("dns-remote"))

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                interface_name = "tun0"
                stack = when (snap.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    TunImplementation.MIXED -> "mixed"
                    else -> "go"
                }
                mtu = snap.mtu
                // sing-box 1.15 removed legacy inbound fields. Sniffing and the
                // inbound domain strategy are emitted as route rule actions below.
                auto_route = true
                strict_route = snap.strictRoute
                address = VpnService.tunAddresses(ipv6Mode).map { "${it.host}/${it.prefixLength}" }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = snap.mixedPort
                // sing-box 1.15 rejects legacy inbound fields on listen inbounds
                // (sniff / sniff_override_destination / domain_strategy); see the
                // route rule actions below for their replacements.
                if (snap.mixedInboundHasAuth) {
                    users = listOf(User().also { u ->
                        u.username = snap.mixedUsername
                        u.password = snap.mixedSecret
                    })
                }
            })
        }

        outbounds = mutableListOf()
        endpoints = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()
            // sing-box 1.15 removed the fork-only "concurrent_dial" route
            // extension; concurrent dialing is now built into the core dialer.
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = snap.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()

                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = groupCache.getOrPut(ownerGid) {
                            captured.groups[ownerGid]
                        }
                        val resolver = ownerGroup
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.subscription?.serverDnsResolver
                            ?.let { sanitizeDnsEntry(it) }
                            ?.takeIf { it.isNotBlank() }

                        if (resolver != null) {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    if (hop.groupId == ownerGid) {
                                        perGroupResolver[ownerGid] = resolver
                                        perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                            .add(host)
                                        hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                    } else {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        } else {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    nonCustomFinalHosts.add(host)
                                }
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }


                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean ->
                            buildSingBoxWireGuardEndpointBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && snap.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            currentOutbound._hack_config_map["detour"] = TAG_FRAGMENT
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                    applyDialerTuning()
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                if (snap.enableTLSFragment) {
                                    route.rules.add(Rule_DefaultOptions().apply {
                                        network = listOf("tcp")
                                        inbound = listOf(tag)
                                        outbound = TAG_FRAGMENT
                                    })
                                }

                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                if (currentOutbound is SingBoxOptions.Endpoint) {
                    // WireGuard & friends live in `endpoints` since sing-box 1.15
                    endpoints.add(currentOutbound)
                } else {
                    outbounds.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { captured.proxiesByGroup[it].orEmpty() }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }
        val runtimeRouterGroups = routerGroups.map { router ->
            RouterRuntimeGroup(
                stableTag = router.stableTag,
                mode = if (router.mode == RouterGroup.MODE_URL_TEST) {
                    RouterRuntimeMode.URL_TEST
                } else {
                    RouterRuntimeMode.SELECTOR
                },
                memberProxyIds = routerMembers[router.id].orEmpty().map { it.proxyId },
                selectedProxyId = router.selectedProxyId,
                id = router.id,
                name = router.name,
                filter = RouterFilterConfig.fromJson(router.matchConfig),
            )
        }
        val routerOutbounds = buildRouterOutbounds(
            runtimeRouterGroups,
            tagMap,
            reservedTags = routerReservedTags(outbounds),
            includeRouterGroups = includeRouterGroups
        )
        outbounds.addAll(routerOutbounds)
        val builtRouterTags = routerOutbounds.mapNotNull { outbound ->
            (outbound.asMap()["tag"] as? String) ?: outbound.tag
        }.toSet()
        val routerTagsById = routerGroups.mapNotNull { router ->
            router.stableTag.takeIf(builtRouterTags::contains)?.let { router.id to it }
        }.toMap()
        // Rules that still point at missing/disabled/empty Router groups fall back to
        // legacy outbound inside resolveRouteOutbound; do not abort config build here.
        routerSelectorTags = routerOutbounds
            .filterIsInstance<Outbound_SelectorOptions>()
            .mapNotNull { outbound ->
                outbound.tag?.takeIf { it.isNotBlank() }?.let { it to it }
            }
            .toMap()
        routerMemberIds = routerGroups.associate { router ->
            router.stableTag to routerMembers[router.id].orEmpty().map { it.proxyId }.toSet()
        }.filterKeys(routerSelectorTags::containsKey)

        routerUrlTestTags = runtimeRouterGroups.filter {
            it.mode == RouterRuntimeMode.URL_TEST && it.stableTag in builtRouterTags
        }.associate { it.id to it.stableTag }

        // Router membership must not change the legacy main selection or outbound=0.
        val mainProxyTag = if (buildSelector) TAG_PROXY else tagMap[proxy.id] ?: TAG_PROXY
        connectionTestTargetTag = mainProxyTag

        // 在应用用户规则之前检查全局模式
        if (!forTest && snap.globalMode) {
            // 全局模式下的规则处理
            
            // 绕过内部网络（如果启用）
            if (snap.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "224.0.0.0/3",
                        "172.16.0.0/12",
                        "127.0.0.0/8",
                        "10.0.0.0/8",
                        "192.168.0.0/16",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                    )
                    outbound = TAG_DIRECT
                })
            }

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(deviceInboundTag)
                outbound = mainProxyTag
            })

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            // 应用用户规则
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }
                    
                    if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                    
		    // 存储ruleset标签和类型信息
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                    
                    // 处理远程ruleset
                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)
                            
                            val tag = generateRemoteRuleSet(url, ruleSets, snap.rulesUpdateInterval)
                            
                            rulesetTags.add(Pair(tag, isIPRuleset))
                            
                            rule_set = (rule_set ?: mutableListOf()).apply {
                                add(tag)
                            }
                        }
                    }

                    if (rule.port.isNotBlank()) {
                        port = mutableListOf<Int>()
                        port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().map {
                            if (it.contains(":")) {
                                port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { port.add(this) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        source_port = mutableListOf<Int>()
                        source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().map {
                            if (it.contains(":")) {
                                source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { source_port.add(this) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                        return DNSRule_DefaultOptions().apply {
                            if (uidList.isNotEmpty()) user_id = uidList
                            domainList?.let { makeSingBoxRule(it) }
                        }
                    }

                    val hasDomainCriteria = !domainList.isNullOrEmpty()
                    val hasIpCriteria =
                        rule.ip.isNotBlank() || rulesetTags.any { it.second }
                    val hasDomainRuleset = rulesetTags.any { !it.second }
                    val isAppOnlyDns =
                        uidList.isNotEmpty() &&
                            !hasDomainCriteria &&
                            !hasIpCriteria &&
                            !hasDomainRuleset &&
                            rule.port.isBlank() &&
                            rule.sourcePort.isBlank() &&
                            rule.network.isBlank() &&
                            rule.source.isBlank() &&
                            rule.protocol.isBlank()
                    val shouldAddDnsRule = hasDomainCriteria || isAppOnlyDns

                    when (rule.outbound) {
                        -1L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-direct"
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    // 只处理ruleset标签，且必须是非IP类型
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-direct"
                                        }
                                    }
                                }
                            }
                        }

                        0L -> {
                            if (shouldAddDnsRule) {
                                if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-fake"
                                    inbound = listOf(deviceInboundTag)
                                    query_type = listOf("A", "AAAA")
                                } else {
                                    userDNSRuleList += makeDnsRuleObj().apply {
                                        server = "dns-remote"
                                    }
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        if (useFakeDns) {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-fake"
                                                inbound = listOf(deviceInboundTag)
                                                query_type = listOf("A", "AAAA")
                                            }
                                        } else {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-remote"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        -2L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    _hack_config_map["action"] = "predefined"
                                    _hack_config_map["rcode"] = "NOERROR"
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            _hack_config_map["action"] = "predefined"
                                            _hack_config_map["rcode"] = "NOERROR"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    outbound = resolveRouteOutbound(rule, mainProxyTag, tagMap, routerTagsById, proxy.id)

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (ruleObj.outbound.isNullOrBlank()) {
                        Toast.makeText(
                            SagerNet.application,
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        // block 改用新的写法
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        if (snap.enableTLSFragment) {
            // sing-box 1.15 removed the fork-only direct-outbound "fragment"
            // extension (a {length, interval} object). TLS fragmentation now
            // lives in the route "tls_fragment" action / TLS options; emitting
            // the legacy object breaks strict parsing. Emit a plain direct
            // outbound so the TAG_FRAGMENT detour keeps working without
            // crashing config decode. (Full migration to "tls_fragment" is a
            // separate follow-up.)
            val fragmentOutbound = Outbound().apply {
                tag = TAG_FRAGMENT
                type = "direct"
            }
            outbounds.add(fragmentOutbound)
        }

        fun isExclusiveCustomHost(host: String): Boolean {
            return hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:${serverAddr}")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(buildDnsServerOptions("dns-local", "local", detour = TAG_DIRECT))

        directDNS.firstOrNull().let {
            dns.servers.add(buildDnsServerOptions(
                tag = "dns-direct",
                address = it ?: throw Exception("No direct DNS, check your settings!"),
                detour = TAG_DIRECT,
                addressResolver = "dns-local"
            ))
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(buildDnsServerOptions(
                tag = "dns-remote",
                address = it ?: throw Exception("No remote DNS, check your settings!"),
                detour = remoteDnsDetour(forTest, mainProxyTag),
                addressResolver = "dns-direct"
            ))
        }
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                tag = TAG_DNS_HOSTS
                _hack_config_map["type"] = "hosts"
                _hack_config_map["predefined"] = dnsHosts
            })
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"
        dns.strategy = dnsStrategy

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            // sing-box 1.15 replaced legacy inbound fields with rule actions
            // (inserted at index 0, so final order is: port 53 hijack, sniff,
            // resolve, dns hijack, then user rules):
            // - inbound "sniff" -> "sniff" action. Non-terminating; must run
            //   before rules that match on the sniffed protocol/domain.
            // - inbound "domain_strategy" -> "resolve" action with the strategy.
            // - "sniff_override_destination" has no sing-box 1.15 equivalent and
            //   is dropped; plain sniffing still works.
            val inboundResolveStrategy = genDomainStrategy(snap.resolveDestination)
            if (inboundResolveStrategy.isNotEmpty()) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "resolve"
                    strategy = inboundResolveStrategy
                })
            }
            if (needSniff) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "sniff"
                })
            }
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (snap.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    tag = "dns-fake"
                    _hack_config_map["type"] = "fakeip"
                    _hack_config_map["inet4_range"] = "198.18.0.0/15"
                    _hack_config_map["inet6_range"] = "fc00::/18"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf(deviceInboundTag)
                    server = "dns-fake"
                    disable_cache = true
                    query_type = listOf("A", "AAAA")
                })
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    _hack_config_map["ip_accept_any"] = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.servers.add(buildDnsServerOptions(
                    tag = serverTag,
                    address = resolver,
                    detour = TAG_DIRECT,
                    addressResolver = if (!resolver.isIpAddress()) "dns-direct" else null
                ))
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
                })
            }
        }

        if (!forTest) _hack_custom_config = snap.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        val allRouterMemberIds = routerGroups.flatMapTo(mutableSetOf<Long>()) { router ->
            routerMembers[router.id].orEmpty().map { member -> member.proxyId }
        }
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            routerSelectorTags,
            routerMemberIds,
            routerUrlTestTags,
            mainUrlTestTag = mainUrlTestTag,
            routerAllMemberIds = allRouterMemberIds,
            connectionTestTargetTag = connectionTestTargetTag,
        )
    }

}

internal fun remoteDnsDetour(forTest: Boolean, activeProxyTag: String): String? =
    if (forTest) null else activeProxyTag

// buildDnsServerOptions converts the legacy sing-box DNS server address string
// (used by NekoBox settings) into the sing-box 1.15+ typed DNS server format.
// The legacy "address" field was removed in sing-box 1.14, so we must emit
// "type" + type-specific fields instead.
internal fun buildDnsServerOptions(
    tag: String,
    address: String,
    detour: String? = null,
    addressResolver: String? = null,
): DNSServerOptions {
    val options = DNSServerOptions().apply {
        this.tag = tag
        // sing-box 1.15 rejects a detour to an empty direct outbound ("makes no
        // sense"): with no detour the DNS transport dials directly, which is
        // the same behavior. Only emit detour for real outbounds.
        if (detour != null && detour != TAG_DIRECT && detour != TAG_BYPASS) this.detour = detour
        // sing-box 1.15 renamed the legacy DNS server "address_resolver" field to
        // "domain_resolver" (a string tag or object form).
        if (addressResolver != null) this._hack_config_map["domain_resolver"] = addressResolver
    }
    when {
        address == "local" -> {
            options._hack_config_map["type"] = "local"
        }
        address == "fakeip" -> {
            options._hack_config_map["type"] = "fakeip"
        }
        else -> {
            val scheme = if (address.contains("://")) {
                address.substringBefore("://").lowercase()
            } else {
                "udp"
            }
            val body = if (address.contains("://")) address.substringAfter("://") else address
            when (scheme) {
                "https", "h3" -> {
                    val url = "https://$body".toHttpUrlOrNull()
                        ?: error("invalid DNS HTTPS URL: $address")
                    options._hack_config_map["type"] = if (scheme == "h3") "h3" else "https"
                    options._hack_config_map["server"] = url.host
                    options._hack_config_map["server_port"] =
                        if (url.port != -1) url.port else 443
                    if (url.encodedPath.isNotBlank() && url.encodedPath != "/") {
                        options._hack_config_map["path"] = buildString {
                            append(url.encodedPath)
                            if (url.encodedQuery != null) {
                                append('?')
                                append(url.encodedQuery)
                            }
                        }
                    } else if (url.encodedQuery != null) {
                        options._hack_config_map["path"] = "/?${url.encodedQuery}"
                    }
                    options._hack_config_map["tls"] = mapOf(
                        "enabled" to true,
                        "server_name" to if (url.host.isIpAddress()) "" else url.host
                    )
                }
                "tls", "tcp", "udp", "quic" -> {
                    val defaultPort = when (scheme) {
                        "tls", "quic" -> 853
                        else -> 53
                    }
                    val (host, port) = parseDnsHostPort(body, defaultPort)
                    options._hack_config_map["type"] = scheme
                    options._hack_config_map["server"] = host
                    options._hack_config_map["server_port"] = port
                    if (scheme == "tls" || scheme == "quic") {
                        options._hack_config_map["tls"] = mapOf(
                            "enabled" to true,
                            "server_name" to if (host.isIpAddress()) "" else host
                        )
                    }
                }
                else -> {
                    require(false) { "unsupported DNS scheme: $scheme" }
                }
            }
        }
    }
    return options
}

private fun parseDnsHostPort(input: String, defaultPort: Int): Pair<String, Int> {
    val s = input.trim()
    if (s.startsWith("[")) {
        val close = s.indexOf("]")
        require(close > 1) { "invalid DNS host: $input" }
        val host = s.substring(1, close)
        val port = if (s.length > close + 1) {
            require(s[close + 1] == ':') { "invalid DNS host: $input" }
            s.substring(close + 2).toIntOrNull()
                ?: error("invalid DNS port: $input")
        } else {
            defaultPort
        }
        require(port in 1..65535) { "invalid DNS port: $input" }
        return host to port
    }
    val lastColon = s.lastIndexOf(":")
    val firstColon = s.indexOf(":")
    if (lastColon != -1 && firstColon == lastColon) {
        val port = s.substring(lastColon + 1).toIntOrNull()
            ?: error("invalid DNS port: $input")
        require(port in 1..65535) { "invalid DNS port: $input" }
        require(s.substring(0, lastColon).isNotBlank()) { "invalid DNS host: $input" }
        return s.substring(0, lastColon) to port
    }
    require(s.isNotBlank() && !s.contains("://")) { "invalid DNS host: $input" }
    return s to defaultPort
}
