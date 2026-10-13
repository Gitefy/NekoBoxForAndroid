package io.nekohasekai.sagernet.database

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SubscriptionRestoreFenceTest {
    @Test fun downloadBeforeRestoreCannotCommitEvenAfterRestoreCompletes() = runBlocking {
        val dir = Files.createTempDirectory("subscription-fence").toFile()
        try {
            val before = RestoreCoordinator.withSubscriptionWrite(dir) { it }
            RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.USER_RESTORE)!!.close()
            var committed = false
            val error = runCatching {
                RestoreCoordinator.withSubscriptionWrite(dir, before) { committed = true }
            }.exceptionOrNull()
            assertTrue(error is RestoreCoordinator.SubscriptionWriteRejected)
            assertFalse(committed)
            assertNotEquals(before, RestoreCoordinator.withSubscriptionWrite(dir) { it })
        } finally { dir.deleteRecursively() }
    }

    @Test fun subscriptionAndRestoreUseTheSameRealFileLock() = runBlocking {
        val dir = Files.createTempDirectory("subscription-lock").toFile()
        try {
            RestoreCoordinator.withSubscriptionWrite(dir) {
                assertNull(RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.USER_RESTORE))
            }
            val restore = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.USER_RESTORE)!!
            try {
                val error = runCatching { RestoreCoordinator.withSubscriptionWrite(dir) { error("must not enter") } }.exceptionOrNull()
                assertTrue(error is RestoreCoordinator.SubscriptionWriteRejected)
            } finally { restore.close() }
            RestoreCoordinator.withSubscriptionWrite(dir) { assertFalse(RestoreCoordinator.isActive()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompleteRecoveryBlocksSubscriptionWritesAndExceptionsReleasePermit() = runBlocking {
        val dir = Files.createTempDirectory("subscription-recovery").toFile()
        try {
            RestoreCoordinator.journalFile(dir).writeText("PREPARED")
            val rejected = runCatching { RestoreCoordinator.withSubscriptionWrite(dir) { error("must not commit") } }.exceptionOrNull()
            assertTrue(rejected is RestoreCoordinator.SubscriptionWriteRejected)
            RestoreCoordinator.journalFile(dir).delete()
            runCatching { RestoreCoordinator.withSubscriptionWrite(dir) { throw IllegalStateException("write failed") } }
            RestoreCoordinator.withSubscriptionWrite(dir) { assertEquals("", it) }
        } finally { dir.deleteRecursively() }
    }
}
