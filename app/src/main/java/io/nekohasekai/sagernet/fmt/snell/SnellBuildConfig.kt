package io.nekohasekai.sagernet.fmt.snell

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    val v = bean.version ?: 4
    // sing-box 1.15 core only supports Snell v4 and v6. Reject the legacy
    // v1/v2/v3 and the incompatible v5 before handing the config to the core,
    // so the user gets a clear, actionable error instead of a core parse failure.
    if (v != 4 && v != 6) {
        throw IllegalArgumentException(
            "Snell v$v is not supported by the current sing-box 1.15 core. Use Snell v4 or v6."
        )
    }
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
        server_port = bean.serverPort
        psk = bean.psk
        if (!bean.userKey.isNullOrBlank()) {
            userkey = bean.userKey
        }
        version = v

        if (bean.network != null && bean.network.isNotBlank()) {
            network = bean.network
        }

        if (bean.version == 6) {
            if (!bean.mode.isNullOrBlank() && bean.mode != "default") {
                mode = bean.mode
            }
            // quic_proxy_mode was a reF1nd fork extension; sing-box 1.15's
            // strict option parsing rejects the unknown field, so it must not
            // be emitted anymore.
        } else if (bean.obfsMode != null && bean.obfsMode.isNotBlank()) {
            obfs_mode = if (bean.version != null && bean.version >= 4 && bean.obfsMode == "tls") "" else bean.obfsMode
            if (obfs_mode.isNotBlank() && bean.obfsHost != null && bean.obfsHost.isNotBlank()) {
                obfs_host = bean.obfsHost
            }
        }

        if (bean.reuse != null && bean.reuse) {
            this.reuse = true
        }
    }
}
