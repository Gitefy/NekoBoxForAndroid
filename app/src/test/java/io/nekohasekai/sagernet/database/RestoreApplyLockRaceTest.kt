package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RestoreApplyLockRaceTest {

    private fun dir() = Files.createTempDirectory("restore-race").toFile()

    @Test
    fun bootRecoveryMustNotFailApplyAsRestoreInProgress() = runBlocking {
        val dir = dir()
        val events = CopyOnWriteArrayList<RestoreCoordinator.LockEvent>()
        RestoreCoordinator.lockSink = { events.add(it) }
        try {
            val boot = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.BOOT_RECOVERY)
            assertNotNull(boot)
            assertFalse(RestoreCoordinator.isActive())
            assertEquals(RestoreCoordinator.LockKind.BOOT_RECOVERY, RestoreCoordinator.readHolderKind(dir))

            val enteredWait = CountDownLatch(1)
            RestoreCoordinator.lockSink = { ev ->
                events.add(ev)
                if (ev.op == "wait") enteredWait.countDown()
            }

            val apply = async(Dispatchers.IO) {
                RestoreCoordinator.acquirePermit(
                    dir,
                    RestoreCoordinator.LockKind.APPLY,
                    cancelled = { false },
                )
            }
            assertTrue(enteredWait.await(5, TimeUnit.SECONDS))
            assertFalse(apply.isCompleted)
            assertTrue(events.any { it.op == "acquired" && it.kind == "BOOT_RECOVERY" })
            assertTrue(events.any { it.op == "rejected" && it.kind == "APPLY" })
            assertTrue(events.any { it.op == "wait" && it.kind == "APPLY" })
            assertFalse(events.any { it.op == "restore-in-progress" && it.kind == "APPLY" })

            boot!!.close()
            val outcome = apply.await()
            assertNotNull(outcome.permit)
            assertNull(outcome.error)
            outcome.permit!!.close()
            assertFalse(RestoreCoordinator.isActive())
        } finally {
            RestoreCoordinator.lockSink = null
        }
    }

    @Test
    fun userRestoreStillFailsApplyImmediately() = runBlocking {
        val dir = dir()
        val held = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.USER_RESTORE)
        assertNotNull(held)
        assertTrue(RestoreCoordinator.isActive())
        val outcome = RestoreCoordinator.acquirePermit(dir, RestoreCoordinator.LockKind.APPLY) { false }
        assertNull(outcome.permit)
        assertEquals(ApplyErrorCodes.RESTORE_IN_PROGRESS, outcome.error)
        held!!.close()
    }

    @Test
    fun applyWaitIsCancellableAndStopDoesNotWait() = runBlocking {
        val dir = dir()
        val boot = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.BOOT_RECOVERY)!!
        val enteredWait = CountDownLatch(1)
        RestoreCoordinator.lockSink = { if (it.op == "wait") enteredWait.countDown() }
        try {
            val waiting = async(Dispatchers.IO) {
                RestoreCoordinator.acquirePermit(dir, RestoreCoordinator.LockKind.APPLY) { false }
            }
            assertTrue(enteredWait.await(5, TimeUnit.SECONDS))
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
            val stopDoesNotAcquire = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.APPLY)
            assertNull(stopDoesNotAcquire)
        } finally {
            RestoreCoordinator.lockSink = null
            boot.close()
        }
    }
}
