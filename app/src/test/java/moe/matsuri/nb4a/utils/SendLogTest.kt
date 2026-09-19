package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendLogTest {
    @Test
    fun crashReportsCapNekoLog() {
        assertTrue(SendLog.isCrashReport("Asteria Crash"))
        assertEquals(SendLog.CRASH_NEKO_LOG_MAX_BYTES, SendLog.nekoLogBudgetBytes("Asteria Crash"))
        assertFalse(SendLog.isCrashReport("Asteria"))
        assertEquals(0L, SendLog.nekoLogBudgetBytes("Asteria"))
    }
}
