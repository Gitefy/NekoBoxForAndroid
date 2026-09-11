package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigSnapshotTest {

    private fun sample(mtu: Int = 1500) = ConfigSnapshot(
        serviceMode = "vpn",
        allowAccess = false,
        remoteDns = "1.1.1.1",
        directDns = "8.8.8.8",
        dnsHosts = "",
        enableDnsRouting = true,
        enableFakeDns = false,
        trafficSniffing = 1,
        ipv6Mode = 0,
        concurrentDial = false,
        enableClashAPI = false,
        logLevel = 0,
        tunImplementation = 0,
        mtu = mtu,
        strictRoute = true,
        mixedPort = 2080,
        mixedInboundHasAuth = false,
        mixedUsername = "",
        mixedSecret = "",
        enableTLSFragment = false,
        globalMode = false,
        bypassLan = false,
        rulesUpdateInterval = "0",
        resolveDestination = false,
        bypassLanInCore = false,
        globalCustomConfig = "",
        domainStrategyRemote = "",
        domainStrategyDirect = "",
        domainStrategyServer = "",
    )

    @Test
    fun sameSnapshotEquals() {
        assertEquals(sample(), sample())
    }

    @Test
    fun mutationAfterCaptureDoesNotChangeSnapshot() {
        val frozen = sample(mtu = 1400)
        val mutated = frozen.copy(mtu = 9000)
        assertEquals(1400, frozen.mtu)
        assertNotEquals(frozen, mutated)
    }

    @Test
    fun frozenRowsDecodeOnceAndIgnoreLaterMutation() {
        val rows = mutableListOf(
            KeyValuePair(Key.MTU).put("1400"),
            KeyValuePair(Key.REMOTE_DNS).put("1.1.1.1"),
            KeyValuePair(Key.ENABLE_FAKEDNS).put(false),
        )
        val frozen = ConfigSnapshot.fromFrozenRows(rows, mixedPortFallback = 2080)
        rows.clear()
        rows.add(KeyValuePair(Key.MTU).put("9000"))
        rows.add(KeyValuePair(Key.REMOTE_DNS).put("8.8.8.8"))
        assertEquals(1400, frozen.mtu)
        assertEquals("1.1.1.1", frozen.remoteDns)
        assertEquals(false, frozen.enableFakeDns)
    }

    @Test
    fun digestIsDeterministicAndCollisionResistantVsHashCode() {
        val a = "custom-config-" + "x".repeat(100)
        val b = "custom-config-" + "y".repeat(100)
        assertEquals(configContentDigest(a), configContentDigest(a))
        assertNotEquals(configContentDigest(a), configContentDigest(b))
        assertEquals(64, configContentDigest(a).length)
    }
}
