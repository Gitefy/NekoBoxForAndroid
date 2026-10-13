package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.R

enum class LogReadabilityCategory {
    VPN,
    CONFIG,
    ROUTER,
    DNS,
    CONNECTION,
    RECONNECT,
    URL_TEST,
    OTHER,
}

enum class LogReadabilitySeverity {
    ERROR,
    WARNING,
}

data class ReadableLogItem(
    val category: LogReadabilityCategory,
    val severity: LogReadabilitySeverity,
    val timestamp: String?,
    val occurrences: Int,
    val messageRes: Int,
    val nextActionRes: Int,
    val details: List<ReadableLogDetail>,
)

data class ReadableLogDetail(
    val timestamp: String?,
    val message: String,
    val occurrences: Int,
)

object LogReadabilityFormatter {
    private val timestampPattern = Regex(
        """^\s*(\d{4}[-/]\d{2}[-/]\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?)""",
    )
    private val ansiEscapePattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val nonIssueLevelPattern = Regex("(?i)^\\s*(?:DEBUG|INFO|TRACE|VERBOSE)(?:\\s*\\[[^]]*])?(?:\\s|:|$)")
    private val severityPrefixPattern = Regex(
        "(?i)^\\s*(?:(?:error|fatal|err|warn|warning|wrn|info|debug|trace)(?:\\s*\\[[^]]*])?|\\[(?:error|fatal|warn|warning)])[ \\t:.-]*",
    )
    private val errorPattern = Regex("(?i)\\b(error|fatal|err)\\b(?!\\s*=\\s*(?:-|$))|ERROR\\[")
    private val warningPattern = Regex("(?i)\\b(warn|warning|wrn)\\b|WARN\\[")
    private val vpnPattern = Regex("(?i)vpnservice|vpn permission|permission.{0,40}vpn|vpn.{0,40}(permission|denied|capture|establish|failed)")
    private val routerPattern = Regex("(?i)strategy group.{0,80}(missing|disabled|no available nodes|unavailable)|router.{0,80}(group.{0,40}(missing|disabled|unavailable)|reference invalid|match configuration is invalid)")
    private val configPattern = Regex("(?i)service failed|failed to start|config.{0,40}(invalid|failed|error|parse|load)|(?:invalid|failed|error).{0,40}(config|configuration)")
    private val dnsPattern = Regex(
        "(?i)dns.{0,80}(?:fail(?:ed|ure)?|\\berror\\b|timeout|no such host|servfail|refused|unreachable|nxdomain)|" +
            "(?:lookup|resolve|query).{0,80}(?:context deadline exceeded|fail(?:ed|ure)?|\\berror\\b|timeout|no such host|servfail|refused|nxdomain)|" +
            "(?:fail(?:ed|ure)?|\\berror\\b|timeout).{0,80}(?:dns|lookup|resolve|query|no such host)|" +
            "temporary failure in name resolution|missing fakeip record|fakeip cache disabled",
    )
    private val urlTestPattern = Regex("(?i)url.?test.{0,80}(fail|error|timeout|unreachable)|(?:fast.?rtt|activeTester).{0,60}(fail|error)")
    private val dialFailurePattern = Regex(
        "(?i)dial (?:tcp|udp).{0,180}(?:i/o timeout|connection refused|network is unreachable|no route to host)",
    )
    private val connectionDeadlinePattern = Regex(
        "(?i)connection: open connection to .{1,300} using outbound/[^:\\r\\n]{1,120}: context deadline exceeded\\s*$",
    )
    private val explicitProbePattern = Regex(
        "(?i)\\[URLTest]|URLTest (?:batch|isolated)|FastRTT|BatchUrlTestRunner|TestInstance",
    )
    private val transportReconnectPattern = Regex(
        "(?i)\\[[^]]+] (?:Tunnel disconnected|Heartbeat timed out|Outbound flush failed|Open datagram terminal error).{0,240}, reconnecting\\.\\.\\.",
    )
    private val transportFailurePattern = Regex(
        "(?i)(?:Initial connection attempt|Reconnection attempt|Stream open|Tunnel).{0,180}(?:failed|rejected|disconnected|timed out|terminal error)",
    )
    private val connectionPattern = Regex(
        "(?i)timeout|connection refused|connection reset|reset by peer|network is unreachable|no route to host|" +
            "operation was canceled|operation was cancelled|context canceled|missing default interface|SOCKS handshake error|socks5: request rejected|use of closed network connection|software caused connection abort|read auth header failed|failed to fill whole buffer|\\beof\\b",
    )

    private data class Accumulator(
        val category: LogReadabilityCategory,
        val severity: LogReadabilitySeverity,
        val timestamp: String?,
        val details: LinkedHashMap<String, DetailAccumulator> = linkedMapOf(),
    ) {
        val occurrences: Int
            get() = details.values.sumOf { it.occurrences }
    }

    private data class DetailAccumulator(
        val timestamp: String?,
        val message: String,
        var occurrences: Int = 1,
    )

    fun summarize(rawLog: String): List<ReadableLogItem> {
        val items = linkedMapOf<Pair<LogReadabilityCategory, LogReadabilitySeverity>, Accumulator>()
        rawLog.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val cleanLine = ansiEscapePattern.replace(line, "")
            val timestampMatch = timestampPattern.find(cleanLine)
            val afterTimestamp = timestampMatch?.let {
                cleanLine.substring(it.range.last + 1).trimStart()
            } ?: cleanLine
            if (nonIssueLevelPattern.containsMatchIn(afterTimestamp)) return@forEach
            val timestamp = timestampMatch?.groupValues?.getOrNull(1)
            val category = categoryOf(cleanLine) ?: return@forEach
            val severity = severityOf(cleanLine) ?: if (category == LogReadabilityCategory.OTHER) {
                return@forEach
            } else {
                LogReadabilitySeverity.WARNING
            }
            val message = messageOf(cleanLine)
            val key = category to severity
            val item = items.getOrPut(key) { Accumulator(category, severity, timestamp) }
            val detail = item.details[message]
            if (detail == null) {
                item.details[message] = DetailAccumulator(timestamp, message)
            } else {
                detail.occurrences++
            }
        }
        return items.values.map { item ->
            val (message, action) = resourcesFor(item.category)
            ReadableLogItem(
                category = item.category,
                severity = item.severity,
                timestamp = item.timestamp,
                occurrences = item.occurrences,
                messageRes = message,
                nextActionRes = action,
                details = item.details.values.map { detail ->
                    ReadableLogDetail(detail.timestamp, detail.message, detail.occurrences)
                },
            )
        }
    }

    private fun messageOf(line: String): String {
        var message = line.trim()
        timestampPattern.find(message)?.let { timestamp ->
            message = message.substring(timestamp.range.last + 1).trimStart()
        }
        return severityPrefixPattern.replaceFirst(message, "").trim().ifBlank { line.trim() }
    }

    private fun categoryOf(line: String): LogReadabilityCategory? = when {
        vpnPattern.containsMatchIn(line) -> LogReadabilityCategory.VPN
        routerPattern.containsMatchIn(line) -> LogReadabilityCategory.ROUTER
        configPattern.containsMatchIn(line) -> LogReadabilityCategory.CONFIG
        transportReconnectPattern.containsMatchIn(line) -> LogReadabilityCategory.RECONNECT
        transportFailurePattern.containsMatchIn(line) -> LogReadabilityCategory.CONNECTION
        (dialFailurePattern.containsMatchIn(line) || connectionDeadlinePattern.containsMatchIn(line)) &&
            !explicitProbePattern.containsMatchIn(line) ->
            LogReadabilityCategory.CONNECTION
        dnsPattern.containsMatchIn(line) -> LogReadabilityCategory.DNS
        urlTestPattern.containsMatchIn(line) -> LogReadabilityCategory.URL_TEST
        connectionPattern.containsMatchIn(line) -> LogReadabilityCategory.CONNECTION
        severityOf(line) != null -> LogReadabilityCategory.OTHER
        else -> null
    }

    private val fatalPattern = Regex("(?i)\\b(fatal|panic)\\b")
    private val clientHandshakeAbortPattern = Regex(
        "(?i)report handshake success.{0,180}(?:connection reset|reset by peer|broken pipe|canceled|cancelled|use of closed network connection)",
    )

    private fun isDirectBypassDialFailure(line: String): Boolean {
        val isDirectBypass = line.contains("using outbound/direct[") || line.contains("using outbound/bypass[")
        if (!isDirectBypass) return false
        return dialFailurePattern.containsMatchIn(line) ||
            connectionDeadlinePattern.containsMatchIn(line) ||
            connectionPattern.containsMatchIn(line)
    }

    private fun isClientHandshakeAbort(line: String): Boolean =
        clientHandshakeAbortPattern.containsMatchIn(line)

    private fun severityOf(line: String): LogReadabilitySeverity? {
        val hasError = errorPattern.containsMatchIn(line)
        val hasWarning = warningPattern.containsMatchIn(line)
        if (!hasError && !hasWarning) return null

        if (fatalPattern.containsMatchIn(line)) return LogReadabilitySeverity.ERROR

        if (hasError && (isDirectBypassDialFailure(line) || isClientHandshakeAbort(line))) {
            return LogReadabilitySeverity.WARNING
        }

        return when {
            hasError -> LogReadabilitySeverity.ERROR
            hasWarning -> LogReadabilitySeverity.WARNING
            else -> null
        }
    }

    private fun resourcesFor(category: LogReadabilityCategory): Pair<Int, Int> = when (category) {
        LogReadabilityCategory.VPN -> R.string.log_readable_vpn_message to R.string.log_readable_vpn_action
        LogReadabilityCategory.CONFIG -> R.string.log_readable_config_message to R.string.log_readable_config_action
        LogReadabilityCategory.ROUTER -> R.string.log_readable_router_message to R.string.log_readable_router_action
        LogReadabilityCategory.DNS -> R.string.log_readable_dns_message to R.string.log_readable_dns_action
        LogReadabilityCategory.CONNECTION -> R.string.log_readable_connection_message to R.string.log_readable_connection_action
        LogReadabilityCategory.RECONNECT -> R.string.log_readable_reconnect_message to R.string.log_readable_reconnect_action
        LogReadabilityCategory.URL_TEST -> R.string.log_readable_url_test_message to R.string.log_readable_url_test_action
        LogReadabilityCategory.OTHER -> R.string.log_readable_other_message to R.string.log_readable_other_action
    }
}
