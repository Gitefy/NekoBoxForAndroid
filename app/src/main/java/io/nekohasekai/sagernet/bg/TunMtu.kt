package io.nekohasekai.sagernet.bg

import com.google.gson.JsonParser

internal fun tunMtu(optionsJson: String, fallback: Int): Int {
    // This is sing-tun.Options marshaled by Go, not the inbound config JSON.
    return JsonParser.parseString(optionsJson).asJsonObject.get("MTU")?.asInt ?: fallback
}

internal data class TunAddress(val host: String, val prefixLength: Int)

internal data class TunPlatformConfig(
    val mtu: Int,
    val addresses: List<TunAddress>,
    val dnsServers: List<String>,
)

// This is the exact sing-tun.Options JSON passed by libcore's OpenTun bridge.
// Android's VpnService.Builder must consume it rather than re-deriving an
// independent address family from DataStore.
internal fun tunPlatformConfig(
    optionsJson: String,
    fallbackMtu: Int,
    fallbackAddresses: List<TunAddress>,
    fallbackDnsServers: List<String>,
): TunPlatformConfig {
    val root = runCatching { JsonParser.parseString(optionsJson).asJsonObject }.getOrNull()
    val addresses = listOf("Inet4Address", "Inet6Address").flatMap { field ->
        root?.getAsJsonArray(field)?.mapNotNull { entry -> parseTunAddress(entry.asString) }.orEmpty()
    }.ifEmpty { fallbackAddresses }
    val dnsServers = root?.getAsJsonArray("DNSAddress")
        ?.mapNotNull { entry -> entry.asString.takeIf { it.isNotBlank() } }
        ?.ifEmpty { fallbackDnsServers }
        ?: fallbackDnsServers
    return TunPlatformConfig(
        mtu = root?.get("MTU")?.asInt?.takeIf { it > 0 } ?: fallbackMtu,
        addresses = addresses,
        dnsServers = dnsServers,
    )
}

private fun parseTunAddress(value: String): TunAddress? {
    val separator = value.lastIndexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return null
    val prefixLength = value.substring(separator + 1).toIntOrNull() ?: return null
    return TunAddress(value.substring(0, separator), prefixLength)
}
