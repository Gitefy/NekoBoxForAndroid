package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPreferenceSetupGateTest {

    @Test
    fun loadingThenReadyConfiguresOnce() {
        val gate = SettingsPreferenceSetupGate()
        var init = 0
        var configure = 0
        var enable = 0
        assertFalse(gate.hasConfigured())
        assertTrue(
            gate.applyReadySequence({ init++ }, { configure++ }, { enable++ }),
        )
        assertFalse(
            gate.applyReadySequence({ init++ }, { configure++ }, { enable++ }),
        )
        assertEquals(1, init)
        assertEquals(1, configure)
        assertEquals(1, enable)
        assertEquals(1, gate.configureInvocations())
    }

    @Test
    fun failedRetryThenReadyConfiguresOnce() {
        val gate = SettingsPreferenceSetupGate()
        var init = 0
        var configure = 0
        var enable = 0
        // Failed: setup is not invoked.
        assertEquals(0, gate.configureInvocations())
        assertTrue(
            gate.applyReadySequence({ init++ }, { configure++ }, { enable++ }),
        )
        assertFalse(
            gate.applyReadySequence({ init++ }, { configure++ }, { enable++ }),
        )
        assertEquals(1, init)
        assertEquals(1, configure)
        assertEquals(1, enable)
        assertEquals(1, gate.configureInvocations())
    }
}
