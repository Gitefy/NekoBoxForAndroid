package io.nekohasekai.sagernet.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreLockLogLevelTest {
    private fun event(op: String, error: String? = null) = RestoreCoordinator.LockEvent(
        op = op,
        kind = "APPLY",
        acquired = op == "acquired",
        phase = "IDLE",
        pid = 1,
        process = "test",
        error = error,
    )

    @Test
    fun successLifecycleIsRoutineNotWarning() {
        listOf(
            "try",
            "acquired",
            "recovery-begin",
            "recovery-end",
            "recoverOnBoot-begin",
            "recoverOnBoot-end",
        ).forEach { op ->
            assertTrue(op, RestoreCoordinator.lockTraceIsRoutine(event(op)))
        }
    }

    @Test
    fun failuresStayWarning() {
        assertFalse(RestoreCoordinator.lockTraceIsRoutine(event("rejected")))
        assertFalse(RestoreCoordinator.lockTraceIsRoutine(event("wait")))
        assertFalse(RestoreCoordinator.lockTraceIsRoutine(event("restore-in-progress", "RESTORE_IN_PROGRESS")))
        assertFalse(RestoreCoordinator.lockTraceIsRoutine(event("recovery-end", "CONFIG_RESTORE_FAILED")))
        assertFalse(RestoreCoordinator.lockTraceIsRoutine(event("acquired", "lock timeout")))
    }
}
