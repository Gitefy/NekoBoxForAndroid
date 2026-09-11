package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NekoLogPolicyTest {
    @Test
    fun loadingDoesNotPermanentlyDisableCommittedLogging() {
        val placeholderLevel = 0
        val committedLevel = 2
        assertFalse(NekoLogPolicy.desiredEnabled(storeReady = false, committedLogLevel = placeholderLevel))
        assertTrue(NekoLogPolicy.desiredEnabled(storeReady = true, committedLogLevel = committedLevel))
        var enabled = NekoLogPolicy.desiredEnabled(false, placeholderLevel)
        enabled = NekoLogPolicy.desiredEnabled(true, committedLevel)
        assertTrue(enabled)
    }

    @Test
    fun committedDisabledLoggingStaysDisabled() {
        assertFalse(NekoLogPolicy.desiredEnabled(storeReady = true, committedLogLevel = 0))
        assertEquals(
            NekoLogPolicy.PageState.DISABLED,
            NekoLogPolicy.pageState(storeReady = true, committedLogLevel = 0, logText = ""),
        )
        assertEquals(
            NekoLogPolicy.PageState.EMPTY,
            NekoLogPolicy.pageState(storeReady = false, committedLogLevel = 0, logText = ""),
        )
        assertEquals(
            NekoLogPolicy.PageState.EMPTY,
            NekoLogPolicy.pageState(storeReady = true, committedLogLevel = 2, logText = "  "),
        )
        assertEquals(
            NekoLogPolicy.PageState.CONTENT,
            NekoLogPolicy.pageState(storeReady = true, committedLogLevel = 2, logText = "[Info] vpn"),
        )
    }
}
