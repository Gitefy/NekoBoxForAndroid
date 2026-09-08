package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class TunMtuTest {
    @Test
    fun usesCoreMtuInsteadOfGlobalDefault() {
        // libcore marshals sing-tun.Options, whose exported field is uppercase MTU.
        assertEquals(1420, tunMtu("""{"MTU":1420}""", 9000))
    }

    @Test
    fun missingMtuKeepsGlobalDefault() {
        assertEquals(9000, tunMtu("{}", 9000))
    }

    @Test
    fun platformConfigUsesCoreAddressFamiliesAndDns() {
        val result = tunPlatformConfig(
            """{"MTU":1420,"Inet6Address":["fdfe:dcba:9876::1/126"],"DNSAddress":["fdfe:dcba:9876::2"]}""",
            fallbackMtu = 9000,
            fallbackAddresses = listOf(TunAddress("172.19.0.1", 30)),
            fallbackDnsServers = listOf("172.19.0.2"),
        )
        assertEquals(1420, result.mtu)
        assertEquals(listOf(TunAddress("fdfe:dcba:9876::1", 126)), result.addresses)
        assertEquals(listOf("fdfe:dcba:9876::2"), result.dnsServers)
    }

    @Test
    fun invalidCoreJsonKeepsOneFallbackContract() {
        val fallback = listOf(TunAddress("172.19.0.1", 30))
        val result = tunPlatformConfig("invalid", 9000, fallback, listOf("172.19.0.2"))
        assertEquals(9000, result.mtu)
        assertEquals(fallback, result.addresses)
        assertEquals(listOf("172.19.0.2"), result.dnsServers)
    }

    @Test
    fun nullCoreAddressFamilyUsesAvailableFamily() {
        val result = tunPlatformConfig(
            """{"MTU":1420,"Inet4Address":null,"Inet6Address":["fdfe:dcba:9876::1/126"],"DNSAddress":null}""",
            fallbackMtu = 9000,
            fallbackAddresses = listOf(TunAddress("172.19.0.1", 30)),
            fallbackDnsServers = listOf("172.19.0.2"),
        )
        assertEquals(listOf(TunAddress("fdfe:dcba:9876::1", 126)), result.addresses)
        assertEquals(listOf("172.19.0.2"), result.dnsServers)
    }
}
