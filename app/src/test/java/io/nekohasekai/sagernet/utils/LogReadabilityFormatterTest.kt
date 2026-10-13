package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogReadabilityFormatterTest {
    @Test
    fun distinguishesFakeIpMappingLossFromSocksAndBypassFailures() {
        val items = LogReadabilityFormatter.summarize(
            """
            ERROR [3256151938 0ms] router: missing fakeip record, try enable `experimental.cache_file`
            ERROR [311269519 0ms] router: missing fakeip record, try enable `experimental.cache_file`
            ERROR [3066890963 21ms] connection: open connection to api.x.com:443 using outbound/selector[US-Media]: socks5: request rejected, code=4
            ERROR [2934906236 15.1s] connection: open connection to 112.60.24.11:13861 using outbound/direct[bypass]: dial tcp 112.60.24.11:13861: i/o timeout
            """.trimIndent(),
        )
        val dns = items.single { it.category == LogReadabilityCategory.DNS }
        assertEquals(2, dns.occurrences)
        assertEquals(LogReadabilitySeverity.ERROR, dns.severity)
        assertTrue(dns.details.all { it.message.contains("missing fakeip record") })
        assertEquals(2, items.filter { it.category == LogReadabilityCategory.CONNECTION }.sumOf { it.occurrences })
        assertTrue(items.none { it.category == LogReadabilityCategory.OTHER })
    }

    @Test
    fun selectorConnectionDeadlinesAreNotDnsOrUnknownFailures() {
        val items = LogReadabilityFormatter.summarize(
            """
            ERROR connection: open connection to dns.google.com:443 using outbound/selector[SG-TG]: context deadline exceeded
            ERROR connection: open connection to mozilla.cloudflare-dns.com:443 using outbound/selector[SG-TG]: context deadline exceeded
            ERROR connection: open connection to 91.108.56.155:5222 using outbound/selector[SG-TG]: context deadline exceeded
            ERROR connection: open connection to example.com:443 using outbound/selector[SG-TG]: lookup example.com: context deadline exceeded
            WARN URLTest batch failed profile=4007 type=15: context deadline exceeded
            """.trimIndent(),
        )
        assertEquals(3, items.filter { it.category == LogReadabilityCategory.CONNECTION }.sumOf { it.occurrences })
        assertEquals(1, items.filter { it.category == LogReadabilityCategory.DNS }.sumOf { it.occurrences })
        assertEquals(1, items.filter { it.category == LogReadabilityCategory.URL_TEST }.sumOf { it.occurrences })
        assertTrue(items.none { it.category == LogReadabilityCategory.OTHER })
    }

    @Test
    fun canceledProxyDialsAreConnectionsRatherThanUnknownErrors() {
        val item = LogReadabilityFormatter.summarize(
            "ERROR outbound/urltest[SG-TG]: dial tcp 18.143.199.172:53041: operation was canceled",
        ).single()
        assertEquals(LogReadabilityCategory.CONNECTION, item.category)
        assertTrue(item.details.single().message.contains("operation was canceled"))
    }

    @Test
    fun reconnectNoticesRemainSeparateFromFailedAttemptsAndRequests() {
        val items = LogReadabilityFormatter.summarize(
            """
            2026/09/30 23:29:16 [plugin] [transport] Tunnel disconnected (peer closed the connection mid-record), reconnecting...
            [plugin] [transport] Heartbeat timed out (>60s without authenticated traffic), reconnecting...
            [plugin] [transport] Reconnection attempt failed: peer closed the connection mid-record
            ERROR connection: open connection to 36.155.230.28:53861 using outbound/direct[bypass]: dial tcp 36.155.230.28:53861: connect: connection refused
            ERROR connection: report handshake success: go: connection reset by peer
            [plugin] [transport] Re-established authenticated Transport session to 127.0.0.1:8443
            """.trimIndent(),
        )
        val reconnect = items.single { it.category == LogReadabilityCategory.RECONNECT }
        assertEquals(2, reconnect.occurrences)
        assertEquals(R.string.log_readable_reconnect_message, reconnect.messageRes)
        val failures = items.filter { it.category == LogReadabilityCategory.CONNECTION }
        assertEquals(3, failures.sumOf { it.occurrences })
        assertTrue(failures.flatMap { it.details }.any { it.message.contains("Reconnection attempt failed") })
        assertTrue(failures.flatMap { it.details }.any { it.message.contains("connection refused") })
        assertEquals(5, items.sumOf { it.occurrences })
    }

    @Test
    fun retainsTransportNativeFailureReasonsAndClassifiesSocksRejectionsAsConnections() {
        val items = LogReadabilityFormatter.summarize(
            """
            [plugin] [transport] Initial connection attempt 1 failed (transport I/O failure: timed out), retrying...
            [plugin] [transport] Reconnection attempt failed: transport I/O failure: timed out
            [plugin] [transport] Stream open response wait failed (15s budget): timed out waiting on channel
            [plugin] [transport] Stream open rejected: upstream rejected
            ERROR connection: open connection using outbound/selector[US-Media]: socks5: request rejected, code=4
            ERROR connection: use of closed network connection
            ERROR connection: software caused connection abort
            [plugin] [transport] Established authenticated Transport session to 127.0.0.1:1
            """.trimIndent(),
        )
        assertTrue(items.all { it.category == LogReadabilityCategory.CONNECTION })
        assertEquals(7, items.sumOf { it.occurrences })
        assertTrue(items.flatMap { it.details }.any { it.message.contains("Reconnection attempt failed") })
    }

    @Test
    fun groupNodeDialTimeoutsAreConnectionsAndRealProbesRemainUrlTests() {
        val items = LogReadabilityFormatter.summarize(
            """
            ERROR outbound/urltest[SG-TG]: dial tcp 18.143.199.172:53041: i/o timeout
            ERROR connection: open connection to mozilla.cloudflare-dns.com:443 using outbound/urltest[SG-TG]: dial tcp 18.143.199.172:53041: i/o timeout
            ERROR connection: open connection to dns.google.com:443 using outbound/urltest[SG-TG]: dial tcp 18.143.199.172:53021: i/o timeout
            WARN URLTest batch failed profile=4007 type=15: context deadline exceeded
            WARN [URLTest] FastRTT req1 fail err=dial tcp 18.143.199.172:53041: i/o timeout
            WARN DNS lookup failed: no such host
            """.trimIndent(),
        )
        assertEquals(listOf(LogReadabilityCategory.CONNECTION, LogReadabilityCategory.URL_TEST, LogReadabilityCategory.DNS), items.map { it.category }.distinct())
        assertEquals(3, items.filter { it.category == LogReadabilityCategory.CONNECTION }.sumOf { it.occurrences })
        assertEquals(2, items.filter { it.category == LogReadabilityCategory.URL_TEST }.sumOf { it.occurrences })
        assertEquals(1, items.filter { it.category == LogReadabilityCategory.DNS }.sumOf { it.occurrences })
    }

    @Test
    fun mapsCommonFailuresAndKeepsUnknownErrorsGeneric() {
        val items = LogReadabilityFormatter.summarize(
            """
            2026-09-27 10:20:30 ERROR Failed to start VpnService
            ERROR service failed to start: invalid config
            ERROR Strategy group SG is disabled
            WARN DNS lookup failed for example.com
            ERROR dial tcp: i/o timeout
            WARN URLTest: activeTester failed
            ERROR unusual failure text
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LogReadabilityCategory.VPN,
                LogReadabilityCategory.CONFIG,
                LogReadabilityCategory.ROUTER,
                LogReadabilityCategory.DNS,
                LogReadabilityCategory.CONNECTION,
                LogReadabilityCategory.URL_TEST,
                LogReadabilityCategory.OTHER,
            ),
            items.map { it.category },
        )
        assertEquals(R.string.log_readable_other_message, items.last().messageRes)
        assertEquals(R.string.log_readable_other_action, items.last().nextActionRes)
    }

    @Test
    fun deduplicatesRepeatedCategoriesAndRetainsTimestampSeverityAndCount() {
        val items = LogReadabilityFormatter.summarize(
            """
            2026-09-27 10:20:30 ERROR DNS lookup failed for one.example
            2026-09-27 10:21:30 ERROR DNS lookup failed for two.example
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        assertEquals(LogReadabilityCategory.DNS, items.single().category)
        assertEquals(LogReadabilitySeverity.ERROR, items.single().severity)
        assertEquals("2026-09-27 10:20:30", items.single().timestamp)
        assertEquals(2, items.single().occurrences)
        assertTrue(items.single().messageRes != 0)
    }

    @Test
    fun keepsDistinctDnsErrorDetailsAndOnlyDeduplicatesIdenticalMessages() {
        val item = LogReadabilityFormatter.summarize(
            """
            2026/09/27 14:18:47 WARN[DNS] lookup example.com: no such host
            2026/09/27 14:18:48 WARN[DNS] lookup example.com: no such host
            2026/09/27 14:18:49 WARN[DNS] lookup api.example.com: i/o timeout
            """.trimIndent(),
        ).single()

        assertEquals(3, item.occurrences)
        assertEquals(
            listOf(
                "lookup example.com: no such host",
                "lookup api.example.com: i/o timeout",
            ),
            item.details.map { it.message },
        )
        assertEquals(listOf(2, 1), item.details.map { it.occurrences })
    }

    @Test
    fun keepsTheOriginalReasonForUnknownErrors() {
        val item = LogReadabilityFormatter.summarize(
            "2026/09/27 14:18:47 ERROR[Runtime] worker exited with status 17",
        ).single()

        assertEquals(LogReadabilityCategory.OTHER, item.category)
        assertEquals("worker exited with status 17", item.details.single().message)
    }

    @Test
    fun ignoresAnsiColoredDebugDnsSuccessLines() {
        val escape = "\u001B"
        val items = LogReadabilityFormatter.summarize(
            """
            ${escape}[37mDEBUG${escape}[0m[0018] [${escape}[38;5;134m629038710${escape}[0m 0ms] dns: exchanged www.google.com NOERROR 600
            ${escape}[37mDEBUG${escape}[0m[0018] [${escape}[38;5;108m3615481948${escape}[0m 24ms] dns: lookup domain rfc01.aurorapipe.com
            ${escape}[37mDEBUG${escape}[0m[0018] [${escape}[38;5;108m3615481948${escape}[0m 24ms] dns: cached rfc01.aurorapipe.com NOERROR 270
            ${escape}[37mDEBUG${escape}[0m[0018] [${escape}[38;5;108m3615481948${escape}[0m 24ms] dns: lookup succeed for rfc01.aurorapipe.com: 38.59.244.113
            2026-09-27 14:18:50 DEBUG[0018] dns: lookup failed for probe.example
            """.trimIndent(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun successfulDnsLookupWithoutSeverityIsNotAWarning() {
        val items = LogReadabilityFormatter.summarize(
            "dns: lookup succeed for example.com: 203.0.113.1",
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun emptyErrorDiagnosticDoesNotUpgradeRestoreLockWarningsToErrors() {
        val items = LogReadabilityFormatter.summarize(
            """
            Warning [RestoreCoordinator] restore-lock op=wait kind=BOOT_RECOVERY acquired=false phase=IDLE pid=22111 process=com.egox:bg holder=- error=-
            Warning [RestoreCoordinator] restore-lock op=rejected kind=BOOT_RECOVERY acquired=false phase=IDLE pid=22111 process=com.egox:bg holder=- error=-
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        assertEquals(LogReadabilityCategory.OTHER, items.single().category)
        assertEquals(LogReadabilitySeverity.WARNING, items.single().severity)
        assertEquals(2, items.single().occurrences)
        assertEquals(2, items.single().details.size)
        assertTrue(items.single().details.all { it.message.contains("error=-") })
    }

    @Test
    fun populatedErrorDiagnosticStillCountsAsAnError() {
        val items = LogReadabilityFormatter.summarize(
            "restore-lock op=failed kind=BOOT_RECOVERY phase=IDLE error=CONFIG_RESTORE_FAILED",
        )

        assertEquals(LogReadabilitySeverity.ERROR, items.single().severity)
        assertTrue(items.single().details.single().message.contains("CONFIG_RESTORE_FAILED"))
    }

    @Test
    fun classifiesMissingDefaultInterfaceAndTransportHandshakeAsConnectionErrors() {
        val items = LogReadabilityFormatter.summarize(
            """
            Error [NetworkManager] network: missing default interface
            Error [plugin] [transport] SOCKS handshake error: read auth header failed: failed to fill whole buffer
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        assertEquals(LogReadabilityCategory.CONNECTION, items.single().category)
        assertEquals(LogReadabilitySeverity.ERROR, items.single().severity)
        assertEquals(2, items.single().occurrences)
        assertEquals(2, items.single().details.size)
        assertTrue(items.single().details[0].message.contains("missing default interface"))
        assertTrue(items.single().details[1].message.contains("read auth header failed"))
        assertTrue(items.single().details[1].message.contains("failed to fill whole buffer"))
    }

    @Test
    fun reportHandshakeSuccessClientResetIsClassifiedAsWarningNotError() {
        val items = LogReadabilityFormatter.summarize(
            """
            ERROR [2726573020 620ms] connection: report handshake success: go: connection reset by peer: connection reset by peer
            ERROR [747945748 622ms] connection: report handshake success: go: connection reset by peer: connection reset by peer
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(LogReadabilityCategory.CONNECTION, item.category)
        assertEquals(LogReadabilitySeverity.WARNING, item.severity)
        assertEquals(2, item.occurrences)
        assertEquals(1, item.details.size)
        assertEquals(2, item.details.single().occurrences)
        assertTrue(item.details.single().message.contains("report handshake success"))
        assertTrue(item.details.single().message.contains("connection reset by peer"))
    }

    @Test
    fun normalDirectAndHandshakeLogsAreNotUpgradedToWarnings() {
        val items = LogReadabilityFormatter.summarize(
            """
            INFO connection: report handshake success
            connection: report handshake success
            INFO connection: open connection to 1.1.1.1:53 using outbound/direct[bypass]
            connection: open connection to 1.1.1.1:53 using outbound/direct[bypass]
            """.trimIndent(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun fatalErrorsAndNonClientHandshakeFailuresRemainErrors() {
        val items = LogReadabilityFormatter.summarize(
            """
            FATAL outbound/direct[bypass]: panic: nil pointer dereference
            ERROR connection: report handshake success: protocol decoding failure
            ERROR connection: open connection to 1.2.3.4:443 using outbound/selector[Proxy]: dial tcp 1.2.3.4:443: connect: connection refused
            """.trimIndent(),
        )

        assertTrue(items.all { it.severity == LogReadabilitySeverity.ERROR })
        assertEquals(3, items.sumOf { it.occurrences })
    }
}
