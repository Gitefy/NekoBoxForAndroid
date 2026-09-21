package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchConfigBuilderTest {

    private fun socks(id: Long, host: String = "192.0.2.1", port: Int = 1080): ProxyEntity {
        return ProxyEntity(id = id, groupId = 1L, userOrder = id).apply {
            putBean(SOCKSBean().apply {
                serverAddress = host
                serverPort = port
                name = "socks-$id"
                applyDefaultValues()
            })
        }
    }

    private fun vmess(id: Long, host: String = "vmess.example.com", port: Int = 443): ProxyEntity {
        return ProxyEntity(id = id, groupId = 1L, userOrder = id).apply {
            putBean(VMessBean().apply {
                serverAddress = host
                serverPort = port
                uuid = "00000000-0000-0000-0000-000000000000"
                name = "vmess-$id"
                applyDefaultValues()
            })
        }
    }

    private fun wireguard(id: Long, host: String = "198.51.100.1", port: Int = 51820): ProxyEntity {
        return ProxyEntity(id = id, groupId = 1L, userOrder = id).apply {
            putBean(WireGuardBean().apply {
                serverAddress = host
                serverPort = port
                localAddress = "10.0.0.2/32"
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                peerPublicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
                name = "wg-$id"
                applyDefaultValues()
            })
        }
    }

    private fun trojanGoPlugin(id: Long): ProxyEntity {
        return ProxyEntity(id = id, groupId = 1L, type = ProxyEntity.TYPE_TROJAN_GO).apply {
            putBean(TrojanGoBean().apply {
                serverAddress = "tg.example.com"
                serverPort = 443
                password = "pass"
                name = "tg-$id"
                applyDefaultValues()
            })
        }
    }

    @Test
    fun emptyProfilesReturnsEmptyResult() {
        val result = BatchConfigBuilder.build(emptyList())
        assertEquals("", result.config)
        assertTrue(result.targetTagMap.isEmpty())
        assertTrue(result.batchEligibleProfiles.isEmpty())
        assertTrue(result.fallbackProfiles.isEmpty())
    }

    @Test
    fun nativeProfilesGenerateUniqueNamespacesAndMinimalConfig() {
        val p1 = socks(101L, "10.0.0.1", 1080)
        val p2 = vmess(102L, "10.0.0.2", 443)
        val p3 = wireguard(103L, "10.0.0.3", 51820)

        val result = BatchConfigBuilder.build(listOf(p1, p2, p3))

        assertEquals(3, result.batchEligibleProfiles.size)
        assertTrue(result.fallbackProfiles.isEmpty())
        assertEquals(mapOf(
            101L to "ut-101-main",
            102L to "ut-102-main",
            103L to "ut-103-main",
        ), result.targetTagMap)

        val json = JsonParser.parseString(result.config).asJsonObject

        // Minimal configuration check: no inbounds, no VPN rules
        val inbounds = json.getAsJsonArray("inbounds")
        assertEquals(0, inbounds.size())

        // Route has no user routing rules
        val route = json.getAsJsonObject("route")
        assertTrue(route.getAsJsonArray("rules").isEmpty)

        // DNS is minimal direct only
        val dns = json.getAsJsonObject("dns")
        assertEquals("dns-direct", dns.get("final").asString)
        val dnsServers = dns.getAsJsonArray("servers")
        val dnsTags = dnsServers.map { it.asJsonObject.get("tag").asString }
        assertTrue(dnsTags.contains("dns-local"))
        assertTrue(dnsTags.contains("dns-direct"))

        // Outbounds and Endpoints
        val outbounds = json.getAsJsonArray("outbounds")
        val outboundTags = outbounds.map { it.asJsonObject.get("tag").asString }
        assertTrue(outboundTags.contains("direct"))
        assertTrue(outboundTags.contains("bypass"))
        assertTrue(outboundTags.contains("block"))
        assertTrue(outboundTags.contains("ut-101-main"))
        assertTrue(outboundTags.contains("ut-102-main"))
        assertFalse(outboundTags.contains("ut-103-main")) // WireGuard is in endpoints!

        val endpoints = json.getAsJsonArray("endpoints")
        val endpointTags = endpoints.map { it.asJsonObject.get("tag").asString }
        assertTrue(endpointTags.contains("ut-103-main"))
    }

    @Test
    fun chainProfilesHaveNamespacedHopsAndCorrectDetours() {
        val hop1 = socks(1L, "hop1.example", 1080)
        val hop2 = socks(2L, "hop2.example", 1080)
        val chainProxy = ProxyEntity(id = 50L, groupId = 1L).apply {
            putBean(ChainBean().apply {
                proxies = listOf(1L, 2L)
                name = "chain-50"
            })
        }

        val proxiesMap = mapOf(1L to hop1, 2L to hop2, 50L to chainProxy)
        val result = BatchConfigBuilder.build(
            profiles = listOf(chainProxy),
            proxies = proxiesMap,
        )

        assertEquals(listOf(chainProxy), result.batchEligibleProfiles)
        assertEquals("ut-50-main", result.targetTagMap[50L])

        val json = JsonParser.parseString(result.config).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }

        val mainHop = outbounds.firstOrNull { it.get("tag").asString == "ut-50-main" }
        assertNotNull("Primary target ut-50-main must exist", mainHop)
        assertEquals("ut-50-hop-1", mainHop!!.get("detour").asString)

        val nextHop = outbounds.firstOrNull { it.get("tag").asString == "ut-50-hop-1" }
        assertNotNull("Next hop ut-50-hop-1 must exist", nextHop)
        assertFalse("Last hop should not detour to non-existent hop", nextHop!!.has("detour"))
    }

    @Test
    fun pluginProfileSeparatedIntoFallback() {
        val nativeSocks = socks(10L, "10.0.0.1", 1080)
        val pluginNode = trojanGoPlugin(20L)

        val result = BatchConfigBuilder.build(listOf(nativeSocks, pluginNode))

        assertEquals(listOf(nativeSocks), result.batchEligibleProfiles)
        assertEquals(listOf(pluginNode), result.fallbackProfiles)
        assertEquals(mapOf(10L to "ut-10-main"), result.targetTagMap)
    }

    @Test
    fun badNodeSeparatedWithoutFailingValidNodes() {
        val validNode = socks(1L, "10.0.0.1", 1080)
        val invalidNode = ProxyEntity(id = 2L, groupId = 1L).apply {
            putBean(SOCKSBean().apply {
                serverAddress = "" // Blank address is invalid
                serverPort = -1
            })
        }
        val anotherValid = socks(3L, "10.0.0.2", 1080)

        val result = BatchConfigBuilder.build(listOf(validNode, invalidNode, anotherValid))

        assertEquals(listOf(validNode, anotherValid), result.batchEligibleProfiles)
        assertEquals(listOf(invalidNode), result.fallbackProfiles)
        assertEquals("ut-1-main", result.targetTagMap[1L])
        assertEquals("ut-3-main", result.targetTagMap[3L])
        assertFalse(result.targetTagMap.containsKey(2L))
    }

    @Test
    fun identicalNodeConfigurationsDoNotCollideTags() {
        // Two separate profiles configured with the exact same name, host, and port
        val p1 = socks(10L, "same.host", 1080)
        val p2 = socks(20L, "same.host", 1080)

        val result = BatchConfigBuilder.build(listOf(p1, p2))
        assertEquals(2, result.batchEligibleProfiles.size)
        assertEquals("ut-10-main", result.targetTagMap[10L])
        assertEquals("ut-20-main", result.targetTagMap[20L])

        val json = JsonParser.parseString(result.config).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertTrue(outbounds.contains("ut-10-main"))
        assertTrue(outbounds.contains("ut-20-main"))
        assertEquals(1, outbounds.count { it == "ut-10-main" })
        assertEquals(1, outbounds.count { it == "ut-20-main" })
    }

    @Test
    fun customOutboundJsonCannotOverrideBatchNamespacedTag() {
        val p1 = socks(10L, "10.0.0.1", 1080).apply {
            val bean = requireBean()
            bean.customOutboundJson = """{"tag":"evil","idle_timeout":"60s"}"""
            putBean(bean)
        }
        val p2 = socks(20L, "10.0.0.2", 1080).apply {
            val bean = requireBean()
            bean.customOutboundJson = """{"tag":"evil","idle_timeout":"90s"}"""
            putBean(bean)
        }

        val result = BatchConfigBuilder.build(listOf(p1, p2))

        assertEquals(2, result.batchEligibleProfiles.size)
        val tagA = result.targetTagMap[10L]
        val tagB = result.targetTagMap[20L]
        assertNotNull(tagA)
        assertNotNull(tagB)
        assertEquals("ut-10-main", tagA)
        assertEquals("ut-20-main", tagB)
        assertTrue("A and B must have unique tags", tagA != tagB)

        val json = JsonParser.parseString(result.config).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }

        // Must not contain any outbound with tag "evil"
        assertFalse("Tag 'evil' from customOutboundJson must not exist", outbounds.any { it.get("tag")?.asString == "evil" })

        // Outbound A has unique namespaced tag and retained idle_timeout
        val outboundA = outbounds.firstOrNull { it.get("tag")?.asString == "ut-10-main" }
        assertNotNull("Outbound A with namespaced tag ut-10-main must exist", outboundA)
        assertEquals("60s", outboundA!!.get("idle_timeout")?.asString)

        // Outbound B has unique namespaced tag and retained idle_timeout
        val outboundB = outbounds.firstOrNull { it.get("tag")?.asString == "ut-20-main" }
        assertNotNull("Outbound B with namespaced tag ut-20-main must exist", outboundB)
        assertEquals("90s", outboundB!!.get("idle_timeout")?.asString)
    }
}

