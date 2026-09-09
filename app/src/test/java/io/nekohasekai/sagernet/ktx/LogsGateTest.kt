package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1 regression coverage for the [Logs] level gate.
 *
 * Disabled levels must no-op *before* touching libcore: on the JVM there is
 * no native lib loaded, so any JNI hop would throw UnsatisfiedLinkError and
 * fail the test. The `error(...)` lambdas additionally prove the message is
 * never evaluated when gated off.
 */
class LogsGateTest {

    @Test
    fun levelZeroSuppressesEverything() {
        Logs.setLevelProvider { 0 }
        assertFalse(Logs.isDebugEnabled())
        assertFalse(Logs.isInfoEnabled())
        Logs.d({ error("debug message must not be evaluated") })
        Logs.i({ error("info message must not be evaluated") })
        Logs.w({ error("warn message must not be evaluated") })
    }

    @Test
    fun levelTwoEnablesInfoOnly() {
        Logs.setLevelProvider { 2 }
        assertTrue(Logs.isInfoEnabled())
        assertFalse(Logs.isDebugEnabled())
        Logs.d({ error("debug message must not be evaluated") })
    }

    @Test
    fun levelThreeEnablesDebug() {
        Logs.setLevelProvider { 3 }
        assertTrue(Logs.isDebugEnabled())
        assertTrue(Logs.isInfoEnabled())
    }

    @Test
    fun brokenProviderFallsBackToWarningsOnly() {
        Logs.setLevelProvider { throw IllegalStateException("store down") }
        assertFalse(Logs.isDebugEnabled())
        assertFalse(Logs.isInfoEnabled())
        Logs.d({ error("debug message must not be evaluated") })
        Logs.i({ error("info message must not be evaluated") })
    }
}
