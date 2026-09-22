package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendLogTest {
    @Test
    fun crashReportsCapNekoLog() {
        assertTrue(SendLog.isCrashReport("EgoX Crash"))
        assertEquals(SendLog.CRASH_NEKO_LOG_MAX_BYTES, SendLog.nekoLogBudgetBytes("EgoX Crash"))
        assertFalse(SendLog.isCrashReport("EgoX"))
        assertEquals(0L, SendLog.nekoLogBudgetBytes("EgoX"))
    }
}
