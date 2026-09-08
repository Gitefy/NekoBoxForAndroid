package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigBuilderDnsTest {
    @Test
    fun remoteDnsUsesActiveProxyDetourOutsideUrlTests() {
        assertEquals("active-proxy", remoteDnsDetour(false, "active-proxy"))
        assertNull(remoteDnsDetour(true, "active-proxy"))
    }

    @Test
    fun preservesDnsHttpsQueryInTypedPath() {
        val options = buildDnsServerOptions(
            tag = "dns-test",
            address = "https://dns.example/dns-query?edns_client_subnet=0.0.0.0",
        )

        assertEquals(
            "/dns-query?edns_client_subnet=0.0.0.0",
            options._hack_config_map["path"],
        )
    }

    @Test
    fun rejectsUnknownDnsScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            buildDnsServerOptions("dns-test", "ftp://dns.example")
        }
    }
}
