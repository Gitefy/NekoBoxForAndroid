package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.canUseSingBox
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxWireGuardEndpointBean
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import com.google.gson.JsonParser
import moe.matsuri.nb4a.utils.JavaUtil.gsonCompact

/**
 * BatchConfigBuilder generates a minimal, isolated sing-box runtime configuration
 * designed specifically for batch URL connectivity testing.
 *
 * It enforces strict namespacing per profile (e.g. ut-${profile.id}-main, ut-${profile.id}-hop-1),
 * omits all VPN/routing/TUN/stats overhead, and identifies profiles requiring isolated
 * fallback (such as external plugins or invalid configurations).
 */
object BatchConfigBuilder {

    data class Result(
        val config: String,
        val targetTagMap: Map<Long, String>,
        val batchEligibleProfiles: List<ProxyEntity>,
        val fallbackProfiles: List<ProxyEntity>,
    )

    fun build(
        profiles: List<ProxyEntity>,
        groups: Map<Long, ProxyGroup?> = emptyMap(),
        proxies: Map<Long, ProxyEntity> = emptyMap(),
        directDns: String = "223.5.5.5",
    ): Result {
        if (profiles.isEmpty()) {
            return Result(
                config = "",
                targetTagMap = emptyMap(),
                batchEligibleProfiles = emptyList(),
                fallbackProfiles = emptyList(),
            )
        }

        val eligible = ArrayList<ProxyEntity>()
        val fallback = ArrayList<ProxyEntity>()

        for (profile in profiles) {
            if (isEligibleForBatch(profile, proxies, groups)) {
                eligible.add(profile)
            } else {
                fallback.add(profile)
            }
        }

        if (eligible.isEmpty()) {
            return Result(
                config = "",
                targetTagMap = emptyMap(),
                batchEligibleProfiles = emptyList(),
                fallbackProfiles = fallback,
            )
        }

        val targetTagMap = LinkedHashMap<Long, String>()
        val outbounds = ArrayList<SingBoxOption>()
        val endpoints = ArrayList<SingBoxOption>()

        // Shared base outbounds
        outbounds.add(Outbound().apply {
            tag = TAG_DIRECT
            type = "direct"
        })
        outbounds.add(Outbound().apply {
            tag = TAG_BYPASS
            type = "direct"
        })
        outbounds.add(Outbound().apply {
            tag = TAG_BLOCK
            type = "block"
        })

        for (profile in eligible) {
            val hops = resolveChain(profile, proxies, groups)
            val primaryTag = "ut-${profile.id}-main"
            targetTagMap[profile.id] = primaryTag

            hops.forEachIndexed { index, hop ->
                val hopTag = if (index == 0) primaryTag else "ut-${profile.id}-hop-$index"
                val outbound = buildNativeOutbound(hop) ?: return@forEachIndexed

                outbound._hack_config_map["tag"] = hopTag
                outbound._hack_config_map["domain_strategy"] = ""

                // Chain detour linking: hop 0 -> hop 1 -> ... -> last hop
                if (index < hops.lastIndex) {
                    val nextHopTag = "ut-${profile.id}-hop-${index + 1}"
                    outbound._hack_config_map["detour"] = nextHopTag
                }

                val muxObj = hop.singMux()
                if (muxObj != null && muxObj.enabled) {
                    outbound._hack_config_map["multiplex"] = muxObj.asMap()
                }

                if (outbound is Endpoint) {
                    endpoints.add(outbound)
                } else {
                    outbounds.add(outbound)
                }
            }
        }

        val dnsOptions = DNSOptions().apply {
            servers = mutableListOf(
                buildDnsServerOptions("dns-local", "local", detour = TAG_DIRECT),
                buildDnsServerOptions(
                    tag = "dns-direct",
                    address = if (directDns.isNotBlank()) directDns else "223.5.5.5",
                    detour = TAG_DIRECT,
                    addressResolver = "dns-local",
                ),
            )
            rules = mutableListOf()
            final_ = "dns-direct"
            strategy = "prefer_ipv4"
        }

        val routeOptions = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        val options = MyOptions().apply {
            log = LogOptions().apply {
                level = if (BuildConfig.DEBUG) "debug" else "warn"
            }
            dns = dnsOptions
            route = routeOptions
            inbounds = mutableListOf()
            this.outbounds = outbounds
            this.endpoints = endpoints
        }

        val configJson = gsonCompact.toJson(options.asMap())
        return Result(
            config = configJson,
            targetTagMap = targetTagMap,
            batchEligibleProfiles = eligible,
            fallbackProfiles = fallback,
        )
    }

    private fun isEligibleForBatch(
        profile: ProxyEntity,
        proxies: Map<Long, ProxyEntity>,
        groups: Map<Long, ProxyGroup?>,
    ): Boolean {
        val hops = resolveChain(profile, proxies, groups)
        if (hops.isEmpty()) return false

        for (hop in hops) {
            // External plugins must be isolated via F1 fallback
            if (hop.needExternal()) return false

            val bean = runCatching { hop.requireBean() }.getOrNull() ?: return false
            when (bean) {
                is WireGuardBean -> {
                    if (bean.privateKey.isBlank() || bean.peerPublicKey.isBlank()) return false
                    if (bean.serverAddress.isBlank()) return false
                }
                is HysteriaBean -> {
                    if (!bean.canUseSingBox()) return false
                    if (bean.serverAddress.isBlank() || bean.serverPort <= 0) return false
                }
                is ConfigBean -> {
                    if (bean.config.isBlank()) return false
                }
                else -> {
                    if (bean.serverAddress.isBlank() || bean.serverPort <= 0) return false
                }
            }
        }
        return true
    }

    fun resolveChain(
        entity: ProxyEntity,
        proxies: Map<Long, ProxyEntity>,
        groups: Map<Long, ProxyGroup?>,
    ): List<ProxyEntity> {
        val visited = HashSet<Long>()

        fun resolveChainInternal(curr: ProxyEntity): List<ProxyEntity> {
            if (!visited.add(curr.id)) {
                Logs.w({ "Detected cyclic proxy chain involving proxy ${curr.id}" })
                return emptyList()
            }
            val bean = runCatching { curr.requireBean() }.getOrNull() ?: return emptyList()
            if (bean is ChainBean) {
                val beanList = ArrayList<ProxyEntity>()
                for (proxyId in bean.proxies) {
                    val item = proxies[proxyId] ?: continue
                    beanList.addAll(resolveChainInternal(item))
                }
                return beanList.asReversed()
            }
            return listOf(curr)
        }

        val group = groups[entity.groupId]
        val frontProxy = group?.frontProxy?.takeIf { it > 0L }?.let { proxies[it] }
        val landingProxy = group?.landingProxy?.takeIf { it > 0L }?.let { proxies[it] }

        val list = ArrayList(resolveChainInternal(entity))
        if (list.isEmpty()) return emptyList()

        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    private fun buildNativeOutbound(entity: ProxyEntity): SingBoxOption? {
        val bean = runCatching { entity.requireBean() }.getOrNull() ?: return null
        val outbound: SingBoxOption = when (bean) {
            is ConfigBean -> CustomSingBoxOption(bean.config)
            is ShadowTLSBean -> buildSingBoxOutboundShadowTLSBean(bean)
            is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean)
            is HysteriaBean -> if (bean.canUseSingBox()) buildSingBoxOutboundHysteriaBean(bean) else return null
            is TuicBean -> buildSingBoxOutboundTuicBean(bean)
            is JuicityBean -> buildSingBoxOutboundJuicityBean(bean)
            is SOCKSBean -> buildSingBoxOutboundSocksBean(bean)
            is ShadowsocksBean -> buildSingBoxOutboundShadowsocksBean(bean)
            is ShadowsocksRBean -> buildSingBoxOutboundShadowsocksRBean(bean)
            is WireGuardBean -> buildSingBoxWireGuardEndpointBean(bean)
            is SSHBean -> buildSingBoxOutboundSSHBean(bean)
            is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
            is SnellBean -> buildSingBoxOutboundSnellBean(bean)
            else -> return null
        }

        try {
            val sUoT = bean.javaClass.getField("sUoT").get(bean)
            if (sUoT is Boolean && sUoT) {
                outbound._hack_config_map["udp_over_tcp"] = true
            }
        } catch (_: Exception) {
        }

        val customJson = bean.customOutboundJson
        if (!customJson.isNullOrBlank()) {
            outbound._hack_custom_config = runCatching {
                val element = JsonParser.parseString(customJson)
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    obj.remove("tag")
                    gsonCompact.toJson(obj)
                } else {
                    customJson
                }
            }.getOrDefault(customJson)
        }
        return outbound
    }
}
