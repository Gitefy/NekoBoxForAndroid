package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class RestoreCoordinatorTest {

    private fun dir() = Files.createTempDirectory("restore-j").toFile()

    private fun row(key: String, value: String) = KeyValuePair(key).put(value)

    private val previousSagerJson =
        """{"version":1,"groups":[{}],"profiles":[],"routerGroups":[],"rules":[]}"""

    @Test
    fun malformedBackupMutatesNothing() {
        val dir = dir()
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun configFailureRollsBackBothSnapshots() {
        val dir = dir()
        val cfg = mutableListOf("old-cfg")
        val sager = mutableListOf("old-sager")
        var first = true
        val out = RestoreCoordinator.commit(
            dir = dir,
            incomingConfig = listOf(row("k", "new")),
            capturePreviousConfig = { listOf(row("k", "old")) },
            capturePreviousSager = { previousSagerJson.toByteArray() },
            restoreConfig = { rows ->
                cfg[0] = rows.first().string ?: ""
                if (first) {
                    first = false
                    false
                } else {
                    true
                }
            },
            restoreSagerIncoming = { sager[0] = "new-sager"; true },
            restoreSagerPrevious = { bytes ->
                sager[0] = String(bytes)
                true
            },
        )
        assertFalse(out.success)
        assertEquals("old", cfg[0])
        assertEquals(previousSagerJson, sager[0])
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun secondDbFailureRestoresBothPreviousStates() {
        val dir = dir()
        val cfg = mutableListOf("old-cfg")
        val sager = mutableListOf("old-sager")
        val out = RestoreCoordinator.commit(
            dir = dir,
            incomingConfig = listOf(row("k", "new")),
            capturePreviousConfig = { listOf(row("k", "old")) },
            capturePreviousSager = { previousSagerJson.toByteArray() },
            restoreConfig = { rows ->
                cfg[0] = rows.first().string ?: ""
                true
            },
            restoreSagerIncoming = { false },
            restoreSagerPrevious = { bytes ->
                sager[0] = String(bytes)
                true
            },
        )
        assertFalse(out.success)
        assertEquals("SAGER_RESTORE_FAILED", out.error)
        assertEquals("old", cfg[0])
        assertEquals(previousSagerJson, sager[0])
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathPreparedWithConfigChangedRestoresBoth() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(previousSagerJson.toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
        var cfg = "NEW_CFG"
        var sager = "NEW_SAGER"
        val recovered = runBlocking {
            RestoreCoordinator.recoverOnBoot(
            dir,
            { rows -> cfg = rows.first().string ?: ""; true },
            { bytes -> sager = String(bytes); true },
        )
        }
        assertTrue(recovered.success)
        assertEquals("old", cfg)
        assertEquals(previousSagerJson, sager)
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathConfigCommittedWithSagerChangedRestoresBoth() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(previousSagerJson.toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.CONFIG_COMMITTED.name)
        var cfg = "NEW_CFG"
        var sager = "NEW_SAGER"
        val recovered = runBlocking {
            RestoreCoordinator.recoverOnBoot(
            dir,
            { rows -> cfg = rows.first().string ?: ""; true },
            { bytes -> sager = String(bytes); true },
        )
        }
        assertTrue(recovered.success)
        assertEquals("old", cfg)
        assertEquals(previousSagerJson, sager)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathSagerCommittedKeepsNewState() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(previousSagerJson.toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.SAGER_COMMITTED.name)
        var cfg = "NEW_CFG"
        var sager = "NEW_SAGER"
        val recovered = runBlocking {
            RestoreCoordinator.recoverOnBoot(
            dir,
            { rows -> cfg = rows.first().string ?: ""; true },
            { bytes -> sager = String(bytes); true },
        )
        }
        assertTrue(recovered.success)
        assertEquals("NEW_CFG", cfg)
        assertEquals("NEW_SAGER", sager)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun bgApplyCannotEnterWhileRestoreLockHeld() {
        val dir = dir()
        val held = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.USER_RESTORE)
        assertNotNull(held)
        assertNull(RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.APPLY))
        assertTrue(RestoreCoordinator.isActive())
        held!!.close()
        val second = RestoreCoordinator.tryExclusivePermit(dir)
        assertNotNull(second)
        second!!.close()
        assertFalse(RestoreCoordinator.isActive())
        assertEquals(ApplyErrorCodes.RESTORE_IN_PROGRESS, RestoreCoordinator.restoreInProgressError())
    }

    @Test
    fun recoveryMustCompleteBeforeStartAllowed() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(previousSagerJson.toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
        val permit = RestoreCoordinator.tryExclusivePermit(dir)!!
        try {
            assertEquals(RestoreCoordinator.Phase.PREPARED, RestoreCoordinator.readPhase(dir))
            RestoreCoordinator.recoverLocked(dir, { true }, { true })
            assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        } finally {
            permit.close()
        }
    }

    @Test
    fun successClearsJournal() {
        val dir = dir()
        val out = RestoreCoordinator.commit(
            dir = dir,
            incomingConfig = listOf(row("k", "new")),
            capturePreviousConfig = { listOf(row("k", "old")) },
            capturePreviousSager = { previousSagerJson.toByteArray() },
            restoreConfig = { true },
            restoreSagerIncoming = { true },
            restoreSagerPrevious = { true },
        )
        assertTrue(out.success)
        assertEquals(RestoreCoordinator.Phase.COMPLETE, out.phase)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
        assertFalse(RestoreCoordinator.snapshotFile(dir).exists())
        assertFalse(RestoreCoordinator.sagerSnapshotFile(dir).exists())
    }

    @Test
    fun emptyConfigSnapshotMustNotWipeLiveSettings() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(ByteArray(0))
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(ByteArray(0))
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
        var cfg = "LIVE"
        var sager = "LIVE_SAGER"
        val recovered = runBlocking {
            RestoreCoordinator.recoverOnBoot(
                dir,
                { rows -> cfg = rows.joinToString { it.key }; true },
                { bytes -> sager = String(bytes); true },
            )
        }
        assertFalse(recovered.success)
        assertEquals("LIVE", cfg)
        assertEquals("LIVE_SAGER", sager)
        assertTrue(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun validEmptySagerJsonIsUsableButMalformedSnapshotsAreNot() {
        val empty = """{"version":1,"groups":[],"profiles":[],"routerGroups":[],"rules":[]}"""
        assertTrue(RestoreCoordinator.hasUsableSagerSnapshot(empty.toByteArray()))
        assertFalse(RestoreCoordinator.hasUsableSagerSnapshot(ByteArray(0)))
        assertFalse(RestoreCoordinator.hasUsableSagerSnapshot("{}".toByteArray()))
        assertFalse(RestoreCoordinator.hasUsableSagerSnapshot("{\"groups\":[],\"profiles\":null,\"rules\":[]}".toByteArray()))
        val usable = """{"version":1,"groups":[{}],"profiles":[],"routerGroups":[],"rules":[]}"""
        assertTrue(RestoreCoordinator.hasUsableSagerSnapshot(usable.toByteArray()))
    }
    @Test fun audit2MissingSettingsSnapshotMustFailClosed() {
        val dir = Files.createTempDirectory("audit2-missing").toFile()
        try {
            RestoreCoordinator.sagerSnapshotFile(dir).writeText("{\"groups\":[{}],\"profiles\":[],\"rules\":[]}")
            RestoreCoordinator.journalFile(dir).writeText("CONFIG_COMMITTED")
            var configCalled = false
            var dbCalled = false
            val out = RestoreCoordinator.recoverLocked(dir, { configCalled = true; true }, { dbCalled = true; true })
            println("missing settings: success=${out.success}, configCalled=$configCalled, journal=${RestoreCoordinator.journalFile(dir).exists()}")
            assertFalse("Missing old settings snapshot treated as successful rollback", out.success)
            assertFalse(configCalled || dbCalled)
            assertTrue(RestoreCoordinator.journalFile(dir).exists())
            assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun audit2MissingSagerSnapshotMustFailClosed() {
        val dir = Files.createTempDirectory("audit2-missing-db").toFile()
        try {
            RestoreCoordinator.snapshotFile(dir).writeBytes(RestoreCoordinator.encodeRows(emptyList()))
            RestoreCoordinator.journalFile(dir).writeText("CONFIG_COMMITTED")
            var dbCalled = false
            var configCalled = false
            val out = RestoreCoordinator.recoverLocked(dir, { configCalled = true; true }, { dbCalled = true; true })
            println("missing db: success=${out.success}, dbCalled=$dbCalled, journal=${RestoreCoordinator.journalFile(dir).exists()}")
            assertFalse("Missing old database snapshot treated as successful rollback", out.success)
            assertFalse(configCalled || dbCalled)
            assertTrue(RestoreCoordinator.journalFile(dir).exists())
            assertTrue(RestoreCoordinator.snapshotFile(dir).exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun audit2ValidEmptySagerMustRestore() {
        val dir = Files.createTempDirectory("audit2-empty-db").toFile()
        try {
            RestoreCoordinator.snapshotFile(dir).writeBytes(RestoreCoordinator.encodeRows(emptyList()))
            RestoreCoordinator.sagerSnapshotFile(dir).writeText("{\"groups\":[],\"profiles\":[],\"routerGroups\":[],\"rules\":[]}")
            RestoreCoordinator.journalFile(dir).writeText("CONFIG_COMMITTED")
            var dbCalled = false
            val out = RestoreCoordinator.recoverLocked(dir, { true }, { dbCalled = true; true })
            println("empty db: success=${out.success}, dbCalled=$dbCalled")
            assertTrue("Valid empty database snapshot rejected", out.success && dbCalled)
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompleteRestoreCannotBeOverwrittenByAnotherImport() {
        val dir = dir()
        try {
            val old = RestoreCoordinator.encodeRows(listOf(row("k", "old")))
            RestoreCoordinator.snapshotFile(dir).writeBytes(old)
            RestoreCoordinator.journalFile(dir).writeText("CONFIG_COMMITTED")
            val out = RestoreCoordinator.commit(dir, emptyList(), { error("must not capture") },
                { error("must not capture") }, { error("must not write") },
                { error("must not write") }, { error("must not write") })
            assertFalse(out.success)
            assertEquals(ApplyErrorCodes.RESTORE_FAILED, out.error)
            assertTrue(old.contentEquals(RestoreCoordinator.snapshotFile(dir).readBytes()))
            assertEquals("CONFIG_COMMITTED", RestoreCoordinator.journalFile(dir).readText())
        } finally { dir.deleteRecursively() }
    }
}
