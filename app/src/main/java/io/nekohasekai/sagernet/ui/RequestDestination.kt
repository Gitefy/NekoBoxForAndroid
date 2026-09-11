package io.nekohasekai.sagernet.ui

import com.google.common.net.InetAddresses

object RequestDestination {
    fun isRawIp(value: String): Boolean {
        if (value.isBlank()) return false
        return InetAddresses.isInetAddress(value)
    }

    fun formatHostPort(address: String, port: Int): String {
        if (address.isBlank()) return if (port > 0) port.toString() else ""
        val host = if (address.contains(':')) "[$address]" else address
        return if (port > 0) "$host:$port" else host
    }
}
