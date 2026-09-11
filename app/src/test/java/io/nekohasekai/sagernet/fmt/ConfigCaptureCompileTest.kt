package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterMember
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCaptureCompileTest {

    private fun settings(mtu: Int = 1400, remoteDns: String = "1.1.1.1"): ConfigSnapshot {
        return ConfigSnapshot.fromFrozenRows(
            listOf(
                KeyValuePair(Key.SERVICE_MODE).put(Key.MODE_PROXY),
                KeyValuePair(Key.MTU).put("$mtu"),
                KeyValuePair(Key.REMOTE_DNS).put(remoteDns),
                KeyValuePair(Key.DIRECT_DNS).put("8.8.8.8"),
                KeyValuePair(Key.ENABLE_FAKEDNS).put(false),
                KeyValuePair(Key.ENABLE_DNS_ROUTING).put(false),
                KeyValuePair(Key.TRAFFIC_SNIFFING).put("0"),
            ),
            mixedPortFallback = 2080,
        )
    }

    private fun socks(id: Long, groupId: Long, host: String, name: String = "n$id"): ProxyEntity {
        return ProxyEntity(id = id, groupId = groupId, userOrder = id).apply {
            putBean(SOCKSBean().apply {
                serverAddress = host
                serverPort = 1080
                this.name = name
                applyDefaultValues()
            })
        }
    }

    private fun captured(
        proxy: ProxyEntity,
        proxies: Map<Long, ProxyEntity>,
        groups: Map<Long, ProxyGroup?>,
        settings: ConfigSnapshot = settings(),
        forTest: Boolean = true,
        forExport: Boolean = false,
        proxiesByGroup: Map<Long, List<ProxyEntity>> = emptyMap(),
        routerGroups: List<RouterGroup> = emptyList(),
        routerMembers: Map<Long, List<RouterMember>> = emptyMap(),
    ) = CapturedConfig(
        proxy = proxy,
        forTest = forTest,
        forExport = forExport,
        settings = settings,
        groups = groups,
        proxies = proxies,
        proxiesByGroup = proxiesByGroup,
        extraRules = emptyList(),
        routerGroups = routerGroups,
        routerMembers = routerMembers,
    )

    @Test
    fun compileUsesFrozenSettingsAndDaoAfterMutation() {
        val group = ProxyGroup(id = 10L, name = "g")
        val proxy = socks(1, 10, "pre.example")
        val backingProxies = mutableMapOf(1L to proxy)
        val backingGroups = mutableMapOf<Long, ProxyGroup?>(10L to group)
        val snap = captured(
            proxy,
            backingProxies.toMap(),
            backingGroups.toMap(),
            settings(mtu = 1400, remoteDns = "1.1.1.1"),
            forTest = false,
        )
        backingProxies[1] = socks(1, 10, "post.example")
        backingGroups[10] = ProxyGroup(id = 10L, name = "mutated")
        val first = compileConfig(snap)
        val second = compileConfig(snap)
        assertTrue(first.config.contains("pre.example"))
        assertFalse(first.config.contains("post.example"))
        assertTrue(first.config.contains("1.1.1.1"))
        assertEquals(first.config, second.config)
        assertEquals(configContentDigest(first.config), configContentDigest(second.config))
    }

    @Test
    fun chainUsesCapturedMembersNotLaterDaoState() {
        val group = ProxyGroup(id = 10L, name = "g")
        val hop = socks(2, 10, "hop-old.example")
        val chain = ProxyEntity(id = 1L, groupId = 10L).apply {
            putBean(ChainBean().apply {
                name = "chain"
                proxies = arrayListOf(2L)
                applyDefaultValues()
            })
        }
        val backing = mutableMapOf(1L to chain, 2L to hop)
        val snap = captured(chain, backing.toMap(), mapOf(10L to group))
        backing[2] = socks(2, 10, "hop-new.example")
        val json = compileConfig(snap).config
        assertTrue(json.contains("hop-old.example"))
        assertFalse(json.contains("hop-new.example"))
    }

    @Test
    fun routerSelectorAndUrlTestStayDistinct() {
        val group = ProxyGroup(id = 10L, name = "g")
        val us = socks(1, 10, "us.example", "US")
        val sg = socks(2, 10, "sg.example", "SG")
        val routers = listOf(
            RouterGroup(id = 7L, stableTag = "router.us", name = "US", mode = RouterGroup.MODE_SELECTOR, enabled = true, selectedProxyId = 1L),
            RouterGroup(id = 8L, stableTag = "router.sg", name = "SG", mode = RouterGroup.MODE_URL_TEST, enabled = true),
        )
        val members = mapOf(
            7L to listOf(RouterMember(routerId = 7L, proxyId = 1L, userOrder = 0)),
            8L to listOf(RouterMember(routerId = 8L, proxyId = 2L, userOrder = 0)),
        )
        val json = compileConfig(
            captured(
                proxy = us,
                proxies = mapOf(1L to us, 2L to sg),
                groups = mapOf(10L to group),
                forTest = false,
                routerGroups = routers,
                routerMembers = members,
            )
        ).config
        assertTrue(json.contains("router.us"))
        assertTrue(json.contains("router.sg"))
        assertTrue(json.contains("selector"))
        assertTrue(json.contains("urltest"))
    }

    @Test
    fun vlessAndAnyTlsStillCompileFromSnapshot() {
        val group = ProxyGroup(id = 10L, name = "g")
        val vless = ProxyEntity(id = 1L, groupId = 10L).apply {
            putBean(VMessBean().apply {
                alterId = -1
                serverAddress = "vless.example"
                serverPort = 443
                uuid = "00000000-0000-0000-0000-000000000001"
                name = "vless"
                applyDefaultValues()
            })
        }
        val anytls = ProxyEntity(id = 2L, groupId = 10L).apply {
            putBean(AnyTLSBean().apply {
                serverAddress = "anytls.example"
                serverPort = 443
                password = "secret"
                name = "anytls"
                applyDefaultValues()
            })
        }
        assertEquals(ProxyEntity.TYPE_VMESS, vless.type)
        assertEquals(true, vless.vmessBean?.isVLESS)
        assertEquals("vless.example", vless.vmessBean?.serverAddress)
        assertEquals(ProxyEntity.TYPE_ANYTLS, anytls.type)
        assertEquals("anytls.example", anytls.anyTLSBean?.serverAddress)
        val socks = socks(3, 10, "plain.example")
        val json = compileConfig(captured(socks, mapOf(3L to socks), mapOf(10L to group))).config
        assertTrue(json.contains("plain.example"))
    }
}
