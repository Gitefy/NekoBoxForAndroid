package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedLanAuthCompileTest {

    private fun socks(): ProxyEntity {
        return ProxyEntity(id = 1L, groupId = 10L, userOrder = 1L).apply {
            putBean(SOCKSBean().apply {
                serverAddress = "example.test"
                serverPort = 1080
                name = "n1"
                applyDefaultValues()
            })
        }
    }

    private fun rows(
        serviceMode: String,
        allowAccess: Boolean?,
        mixedLanAuth: Boolean?,
        username: String = "neko",
        secret: String = "secret-token",
    ): List<KeyValuePair> {
        val list = mutableListOf(
            KeyValuePair(Key.SERVICE_MODE).put(serviceMode),
            KeyValuePair(Key.MIXED_PORT).put("2080"),
            KeyValuePair(Key.MIXED_USERNAME_PREF).put(username),
            KeyValuePair(Key.MIXED_SECRET).put(secret),
            KeyValuePair(Key.ENABLE_FAKEDNS).put(false),
            KeyValuePair(Key.ENABLE_DNS_ROUTING).put(false),
            KeyValuePair(Key.TRAFFIC_SNIFFING).put("0"),
            KeyValuePair(Key.REMOTE_DNS).put("1.1.1.1"),
            KeyValuePair(Key.DIRECT_DNS).put("8.8.8.8"),
        )
        if (allowAccess != null) list.add(KeyValuePair(Key.ALLOW_ACCESS).put(allowAccess))
        if (mixedLanAuth != null) list.add(KeyValuePair(Key.MIXED_LAN_AUTH).put(mixedLanAuth))
        return list
    }

    private fun compile(
        serviceMode: String,
        allowAccess: Boolean?,
        mixedLanAuth: Boolean?,
        forTest: Boolean = false,
    ): Pair<ConfigSnapshot, String> {
        val snap = ConfigSnapshot.fromFrozenRows(
            rows(serviceMode, allowAccess, mixedLanAuth),
            mixedPortFallback = 2080,
        )
        val proxy = socks()
        val json = compileConfig(
            CapturedConfig(
                proxy = proxy,
                forTest = forTest,
                forExport = false,
                settings = snap,
                groups = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
                proxies = mapOf(1L to proxy),
                proxiesByGroup = emptyMap(),
                extraRules = emptyList(),
                routerGroups = emptyList(),
                routerMembers = emptyMap(),
            )
        ).config
        return snap to json
    }

    private fun mixedInbound(json: String): JsonObject? {
        val inbounds = JsonParser.parseString(json).asJsonObject.getAsJsonArray("inbounds") ?: return null
        for (el in inbounds) {
            val obj = el.asJsonObject
            if (obj.get("tag")?.asString == TAG_MIXED) return obj
        }
        return null
    }

    @Test
    fun vpnLocalhostKeepsAuthWhenLanAuthOff() {
        val (snap, json) = compile(Key.MODE_VPN, allowAccess = false, mixedLanAuth = false)
        assertTrue(snap.mixedInboundHasAuth)
        val mixed = mixedInbound(json)!!
        assertEquals("127.0.0.1", mixed.get("listen").asString)
        val users = mixed.getAsJsonArray("users")
        assertNotNull(users)
        assertEquals(1, users.size())
        assertEquals("neko", users[0].asJsonObject.get("username").asString)
        assertEquals("secret-token", users[0].asJsonObject.get("password").asString)
    }

    @Test
    fun vpnLanAuthOnEmitsUsersOnAllInterfaces() {
        val (snap, json) = compile(Key.MODE_VPN, allowAccess = true, mixedLanAuth = true)
        assertTrue(snap.mixedInboundHasAuth)
        val mixed = mixedInbound(json)!!
        assertEquals("0.0.0.0", mixed.get("listen").asString)
        val users = mixed.getAsJsonArray("users")
        assertNotNull(users)
        assertEquals(1, users.size())
    }

    @Test
    fun vpnLanAuthOffOmitsUsersOnAllInterfaces() {
        val (snap, json) = compile(Key.MODE_VPN, allowAccess = true, mixedLanAuth = false)
        assertFalse(snap.mixedInboundHasAuth)
        val mixed = mixedInbound(json)!!
        assertEquals("0.0.0.0", mixed.get("listen").asString)
        assertTrue(!mixed.has("users") || mixed.get("users").isJsonNull)
    }

    @Test
    fun proxyModeNeverEmitsMixedUsers() {
        val (snap, json) = compile(Key.MODE_PROXY, allowAccess = true, mixedLanAuth = true)
        assertFalse(snap.mixedInboundHasAuth)
        val mixed = mixedInbound(json)!!
        assertEquals("0.0.0.0", mixed.get("listen").asString)
        assertTrue(!mixed.has("users") || mixed.get("users").isJsonNull)
    }

    @Test
    fun missingMixedLanAuthDefaultsTrue() {
        val snap = ConfigSnapshot.fromFrozenRows(
            rows(Key.MODE_VPN, allowAccess = true, mixedLanAuth = null),
            mixedPortFallback = 2080,
        )
        assertTrue(snap.mixedLanAuth)
        assertTrue(snap.mixedInboundHasAuth)
    }

    @Test
    fun forTestDoesNotExposeMixedInbound() {
        val (_, json) = compile(
            Key.MODE_VPN,
            allowAccess = true,
            mixedLanAuth = false,
            forTest = true,
        )
        assertNull(mixedInbound(json))
    }
}
