package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import moe.matsuri.nb4a.utils.JavaUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigJsonTest {
    @Test
    fun compactAndPrettyParseToTheSameJsonElement() {
        val map = linkedMapOf<String, Any>(
            "type" to "vless",
            "server" to "example.com",
            "server_port" to 443,
            "nested" to linkedMapOf(
                "tls" to linkedMapOf("enabled" to true, "server_name" to "example.com"),
            ),
            "outbounds" to listOf("us-1", "sg-1"),
        )
        val pretty = JavaUtil.gson.toJson(map)
        val compact = encodeRuntimeConfig(map, pretty = false)
        val exported = encodeRuntimeConfig(map, pretty = true)
        assertEquals(pretty, exported)
        assertTrue(pretty.contains("\n"))
        assertFalse(compact.contains("\n"))
        assertEquals(JsonParser.parseString(pretty), JsonParser.parseString(compact))
    }
}
