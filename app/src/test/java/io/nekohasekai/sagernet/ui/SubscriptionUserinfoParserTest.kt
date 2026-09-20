package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUserinfoParserTest {
    @Test
    fun parsesTypicalClashUserinfo() {
        val stats = SubscriptionUserinfoParser.parse(
            "upload=1024; download=2048; total=4096; expire=1700000000"
        )
        assertEquals(3072L, stats.used)
        assertEquals(4096L, stats.total)
        assertEquals(1700000000L, stats.expireEpochSec)
    }

    @Test
    fun missingFieldsStayZeroAndNull() {
        val stats = SubscriptionUserinfoParser.parse("upload=10")
        assertEquals(10L, stats.used)
        assertEquals(0L, stats.total)
        assertNull(stats.expireEpochSec)
    }

    @Test
    fun usesFirstCaptureWhenKeyRepeats() {
        val stats = SubscriptionUserinfoParser.parse("download=1 download=99 upload=2 total=8 expire=5 expire=9")
        assertEquals(3L, stats.used)
        assertEquals(8L, stats.total)
        assertEquals(5L, stats.expireEpochSec)
    }

    @Test
    fun firstGroupMatchesLegacyToRegexFindAll() {
        val text = "upload=11; upload=22"
        val legacy = "upload=([0-9]+)".toRegex().findAll(text).mapNotNull {
            if (it.groupValues.size > 1) it.groupValues[1] else null
        }.firstOrNull()
        assertEquals(legacy, SubscriptionUserinfoParser.firstGroup(Regex("upload=([0-9]+)"), text))
    }
}
