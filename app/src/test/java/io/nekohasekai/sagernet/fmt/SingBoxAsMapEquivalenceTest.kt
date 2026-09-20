package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SelectorOptions
import moe.matsuri.nb4a.utils.JavaUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class SingBoxAsMapEquivalenceTest {
    @Test
    fun treeAsMapMatchesLegacyStringRoundTrip() {
        val options = listOf(
            customDirect(),
            selectorWithHackTag(),
            myOptionsWithDns(),
        )
        for (option in options) {
            val viaTree = option.asMap()
            val viaString = option.asMapViaJsonString()
            assertEquals(
                JsonParser.parseString(JavaUtil.gsonCompact.toJson(viaString)),
                JsonParser.parseString(JavaUtil.gsonCompact.toJson(viaTree)),
            )
        }
    }

    private fun customDirect() = SingBoxOptions.CustomSingBoxOption(
        "{\"type\":\"direct\",\"nested\":{\"enabled\":true},\"port\":443}"
    )

    private fun selectorWithHackTag() = Outbound_SelectorOptions().apply {
        tag = "router.us"
        type = "selector"
        outbounds = listOf("us-1", "us-2")
        default_ = "us-1"
        _hack_config_map["interrupt_exist_connections"] = true
        _hack_custom_config = "{\"idle_timeout\":\"60s\"}"
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
}
