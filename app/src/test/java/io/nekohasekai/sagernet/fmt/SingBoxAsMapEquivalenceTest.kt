package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import moe.matsuri.nb4a.SingBoxOptions.DirectOutboundOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SelectorOptions
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.utils.JavaUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class SingBoxAsMapEquivalenceTest {
    @Test
    fun treeAsMapMatchesLegacyStringRoundTrip() {
        typedCases().forEach { assertTreeEqualsLegacy(it) }
    }

    @Test
    fun customJsonLiteralsMatchLegacyRoundTrip() {
        jsonLiteralCases().forEach { json ->
            assertTreeEqualsLegacy(SingBoxOptions.CustomSingBoxOption(json))
        }
    }

    @Test
    fun hackMapNumbersAndNestedStructuresMatchLegacy() {
        val outbound = Outbound_SelectorOptions().apply {
            tag = "router.us"
            type = "selector"
            outbounds = listOf("us-1", "us-2")
            default_ = "us-1"
            _hack_config_map["zero"] = 0
            _hack_config_map["neg"] = -1
            _hack_config_map["one"] = 1
            _hack_config_map["flag"] = true
            _hack_config_map["empty_obj"] = emptyMap<String, Any>()
            _hack_config_map["empty_arr"] = emptyList<Any>()
            _hack_config_map["nested"] = mapOf("k" to listOf(1, 2))
            _hack_custom_config = """{"unicode":"日本語🎉","escaped":"a\\b\n\t\"q"}"""
        }
        assertTreeEqualsLegacy(outbound)
    }

    private fun assertTreeEqualsLegacy(option: SingBoxOptions.SingBoxOption) {
        val viaTree = option.asMap()
        val viaString = option.asMapViaJsonString()
        assertEquals(
            JavaUtil.gsonCompact.toJsonTree(viaString),
            JavaUtil.gsonCompact.toJsonTree(viaTree),
        )
        assertEquals(
            JsonParser.parseString(JavaUtil.gsonCompact.toJson(viaString)),
            JsonParser.parseString(JavaUtil.gsonCompact.toJson(viaTree)),
        )
    }

    private fun typedCases(): List<SingBoxOptions.SingBoxOption> = listOf(
        customDirect(),
        selectorWithHackTag(),
        myOptionsWithDns(),
        outboundSocks(),
        routeRule(),
        myOptionsWithRoute(),
        DirectOutboundOptions().apply {
            connect_timeout = Long.MAX_VALUE
            fallback_delay = Long.MIN_VALUE
            tcp_fast_open = false
        },
        DNSOptions().apply {
            strategy = "prefer_ipv4"
            disable_cache = true
            reverse_mapping = false
        },
        Outbound().apply { tag = "omit"; type = "direct" },
    )

    private fun jsonLiteralCases(): List<String> = listOf(
        """{"n":0}""",
        """{"n":-0}""",
        """{"n":1}""",
        """{"n":-1}""",
        """{"n":1.0}""",
        """{"n":0.0}""",
        """{"n":1e2}""",
        """{"n":1E-2}""",
        """{"n":9223372036854775807}""",
        """{"n":-9223372036854775808}""",
        """{"n":1.7976931348623157e308}""",
        """{"n":5e-324}""",
        """{"n":null}""",
        """{"n":true}""",
        """{"n":false}""",
        """{"n":{}}""",
        """{"n":[]}""",
        """{"n":{"a":{"b":1}}}""",
        """{"n":[[1],2]}""",
        """{"n":"日本語🎉"}""",
        """{"n":"a\\b\n\t\"q"}""",
        """{"type":"direct","nested":{"enabled":true},"port":443}""",
    )

    private fun customDirect() = SingBoxOptions.CustomSingBoxOption(
        """{"type":"direct","nested":{"enabled":true},"port":443}"""
    )

    private fun selectorWithHackTag() = Outbound_SelectorOptions().apply {
        tag = "router.us"
        type = "selector"
        outbounds = listOf("us-1", "us-2")
        default_ = "us-1"
        _hack_config_map["interrupt_exist_connections"] = true
        _hack_custom_config = """{"idle_timeout":"60s"}"""
    }

    private fun myOptionsWithDns() = MyOptions().apply {
        dns = DNSOptions().apply {
            strategy = "prefer_ipv4"
        }
        outbounds = listOf(Outbound().apply {
            tag = "proxy"
            type = "socks"
        })
    }

    private fun outboundSocks() = Outbound().apply {
        tag = "socks-out"
        type = "socks"
    }

    private fun routeRule() = Rule_DefaultOptions().apply {
        type = "default"
        outbound = "router.us"
        domain = listOf("example.com")
        invert = false
    }

    private fun myOptionsWithRoute() = MyOptions().apply {
        dns = DNSOptions().apply { final_ = "local" }
        route = RouteOptions().apply {
            final_ = "proxy"
            auto_detect_interface = true
            rules = listOf(
                Rule_DefaultOptions().apply {
                    outbound = "router.us"
                    domain_suffix = listOf("google.com")
                }
            )
        }
        outbounds = listOf(
            Outbound_SelectorOptions().apply {
                tag = "router.us"
                type = "selector"
                outbounds = listOf("a")
            }
        )
    }
}
