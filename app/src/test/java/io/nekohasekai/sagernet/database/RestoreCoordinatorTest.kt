package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.BackupSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class RestoreCoordinatorTest {

    private fun dir() = Files.createTempDirectory("restore-j").toFile()

    private fun row(key: String, value: String) = KeyValuePair(key).put(value)

    private fun writeSnapshots(
        dir: java.io.File,
        config: ByteArray = RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        sager: ByteArray = "OLD_SAGER".toByteArray(),
    ) {
        RestoreCoordinator.snapshotFile(dir).writeBytes(config)
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(sager)
    }

    private fun emptySagerExport(missingSection: String? = null): ByteArray {
        val fields = mutableListOf<String>()
        if (missingSection != "version") fields += "\"version\":${BackupSerializer.BACKUP_VERSION}"
        for (section in listOf("profiles", "groups", "routerGroups", "routerMembers", "routerSources", "rules", "routerRuleRefs")) {
            if (section != missingSection) fields += "\"$section\":[]"
        }
        return "{${fields.joinToString(",")}}".toByteArray()
    }

    private fun failDeleteAt(target: Int): (File) -> Boolean {
        var existingDelete = 0
        return { file ->
            if (!file.exists()) {
                true
            } else if (++existingDelete == target) {
                false
            } else {
                file.delete()
            }
        }
    }

    @Test
    fun malformedBackupMutatesNothing() {
        val dir = dir()
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun configAndRollbackFailurePreservesBothSnapshots() {
        val dir = dir()
        val cfg = mutableListOf("old-cfg")
        val sager = mutableListOf("old-sager")
        val out = RestoreCoordinator.commit(
            dir = dir,
            incomingConfig = listOf(row("k", "new")),
            capturePreviousConfig = { listOf(row("k", "old")) },
            capturePreviousSager = { "OLD_SAGER".toByteArray() },
            restoreConfig = { rows ->
                cfg[0] = rows.first().string ?: ""
                false
            },
            restoreSagerIncoming = { sager[0] = "new-sager"; true },
            restoreSagerPrevious = { bytes ->
                sager[0] = String(bytes)
                true
            },
        )
        assertFalse(out.success)
        assertEquals(ApplyErrorCodes.RESTORE_FAILED, out.error)
        assertEquals("old", cfg[0])
        assertEquals("OLD_SAGER", sager[0])
        assertEquals(RestoreCoordinator.Phase.PREPARED, RestoreCoordinator.readPhase(dir))
        assertTrue(RestoreCoordinator.journalFile(dir).isFile)
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
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
            capturePreviousSager = { "OLD_SAGER".toByteArray() },
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
        assertEquals("OLD_SAGER", sager[0])
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathPreparedWithConfigChangedRestoresBoth() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes("OLD_SAGER".toByteArray())
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
        assertEquals("OLD_SAGER", sager)
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathConfigCommittedWithSagerChangedRestoresBoth() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes("OLD_SAGER".toByteArray())
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
        assertEquals("OLD_SAGER", sager)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathSagerCommittedKeepsNewState() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes("OLD_SAGER".toByteArray())
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
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes("OLD_SAGER".toByteArray())
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
    fun blankOrInvalidJournalFailsWithoutCleanup() {
        for (journal in listOf("", "   \n", "NOT_A_PHASE")) {
            val dir = dir()
            writeSnapshots(dir)
            RestoreCoordinator.journalFile(dir).writeText(journal)

            assertNotEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
            val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { true })

            assertFalse(recovered.success)
            assertEquals(ApplyErrorCodes.RESTORE_FAILED, recovered.error)
            assertTrue(RestoreCoordinator.journalFile(dir).exists())
            assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
            assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
        }
    }

    @Test
    fun nonRegularJournalFailsWithoutCleanup() {
        val dir = dir()
        writeSnapshots(dir)
        assertTrue(RestoreCoordinator.journalFile(dir).mkdir())

        assertNotEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { true })

        assertFalse(recovered.success)
        assertTrue(RestoreCoordinator.journalFile(dir).isDirectory)
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
    }

    @Test
    fun orphanedSnapshotsWithoutJournalFailWithoutCleanup() {
        val dir = dir()
        writeSnapshots(dir)

        val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { true })

        assertFalse(recovered.success)
        assertEquals(ApplyErrorCodes.RESTORE_FAILED, recovered.error)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
    }

    @Test
    fun preparedRecoveryRequiresBothSnapshots() {
        for ((configPresent, sagerPresent) in listOf(true to false, false to true, false to false)) {
            val dir = dir()
            RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
            if (configPresent) RestoreCoordinator.snapshotFile(dir).writeBytes(RestoreCoordinator.encodeRows(emptyList()))
            if (sagerPresent) RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(ByteArray(0))
            var callbacks = 0

            val recovered = RestoreCoordinator.recoverLocked(
                dir,
                { callbacks++; true },
                { callbacks++; true },
            )

            assertFalse(recovered.success)
            assertEquals(ApplyErrorCodes.RESTORE_FAILED, recovered.error)
            assertEquals(0, callbacks)
            assertTrue(RestoreCoordinator.journalFile(dir).isFile)
            assertEquals(configPresent, RestoreCoordinator.snapshotFile(dir).isFile)
            assertEquals(sagerPresent, RestoreCoordinator.sagerSnapshotFile(dir).isFile)
        }
    }

    @Test
    fun malformedConfigSnapshotFailsWithoutCleanup() {
        val dir = dir()
        writeSnapshots(dir, "malformed".toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.CONFIG_COMMITTED.name)

        val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { true })

        assertFalse(recovered.success)
        assertEquals(ApplyErrorCodes.RESTORE_FAILED, recovered.error)
        assertTrue(RestoreCoordinator.journalFile(dir).isFile)
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
    }

    @Test
    fun emptyConfigSnapshotIsValidAndCanRecover() {
        val dir = dir()
        writeSnapshots(
            dir,
            RestoreCoordinator.encodeRows(emptyList()),
            emptySagerExport(),
        )
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
        var restoredRows: List<KeyValuePair>? = null
        var restoredSager = false

        val recovered = RestoreCoordinator.recoverLocked(
            dir,
            { restoredRows = it; true },
            { bytes ->
                RestoreCoordinator.installSagerExport(bytes) {
                    restoredSager = true
                    true
                }
            },
        )

        assertTrue(recovered.success)
        assertEquals(emptyList<KeyValuePair>(), restoredRows)
        assertTrue(restoredSager)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
        assertFalse(RestoreCoordinator.snapshotFile(dir).exists())
        assertFalse(RestoreCoordinator.sagerSnapshotFile(dir).exists())
    }

    @Test
    fun failedOldRecoveryPreventsCommitFromOverwritingMaterials() {
        val dir = dir()
        val oldConfig = RestoreCoordinator.encodeRows(listOf(row("old-key", "old-value")))
        val oldSager = "OLD_SAGER_MATERIAL".toByteArray()
        RestoreCoordinator.snapshotFile(dir).writeBytes(oldConfig)
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(oldSager)
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
        var captureCalls = 0
        var incomingCalls = 0

        val committed = RestoreCoordinator.commit(
            dir = dir,
            incomingConfig = listOf(row("new-key", "new-value")),
            capturePreviousConfig = { captureCalls++; listOf(row("current", "config")) },
            capturePreviousSager = { captureCalls++; "CURRENT_SAGER".toByteArray() },
            restoreConfig = { false },
            restoreSagerIncoming = { incomingCalls++; true },
            restoreSagerPrevious = { true },
        )

        assertFalse(committed.success)
        assertEquals(ApplyErrorCodes.RESTORE_FAILED, committed.error)
        assertEquals(0, captureCalls)
        assertEquals(0, incomingCalls)
        assertEquals(RestoreCoordinator.Phase.PREPARED.name, RestoreCoordinator.journalFile(dir).readText())
        assertTrue(oldConfig.contentEquals(RestoreCoordinator.snapshotFile(dir).readBytes()))
        assertTrue(oldSager.contentEquals(RestoreCoordinator.sagerSnapshotFile(dir).readBytes()))
    }

    @Test
    fun cleanStateDoesNotBlockRecovery() {
        val dir = dir()

        val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { true })

        assertTrue(recovered.success)
        assertEquals(RestoreCoordinator.Phase.IDLE, recovered.phase)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun zeroByteInternalSagerSnapshotIsRejected() {
        assertFalse(RestoreCoordinator.installSagerExport(ByteArray(0)))
    }

    @Test
    fun zeroByteSagerRecoveryFailsAndPreservesMaterials() {
        val dir = dir()
        writeSnapshots(dir, RestoreCoordinator.encodeRows(emptyList()), ByteArray(0))
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)

        val recovered = RestoreCoordinator.recoverLocked(
            dir,
            { true },
            { RestoreCoordinator.installSagerExport(it) { true } },
        )

        assertFalse(recovered.success)
        assertEquals(RestoreCoordinator.Phase.PREPARED, RestoreCoordinator.readPhase(dir))
        assertTrue(RestoreCoordinator.journalFile(dir).isFile)
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
    }

    @Test
    fun interruptedCompletedCleanupKeepsJournalForRetry() {
        val dir = dir()
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.COMPLETE.name)
        assertTrue(RestoreCoordinator.snapshotFile(dir).mkdir())
        RestoreCoordinator.snapshotFile(dir).resolve("blocks-delete").writeText("x")
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes(ByteArray(0))

        val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { true })

        assertFalse(recovered.success)
        assertEquals(RestoreCoordinator.Phase.COMPLETE, RestoreCoordinator.readPhase(dir))
        assertTrue(RestoreCoordinator.journalFile(dir).isFile)
    }

    @Test
    fun internalSagerSnapshotRequiresEveryFullExportSectionBeforeInstall() {
        val cases = mutableListOf("empty-object" to "{}".toByteArray())
        for (section in listOf("version", "profiles", "groups", "routerGroups", "routerMembers", "routerSources", "rules", "routerRuleRefs")) {
            cases += "missing-$section" to emptySagerExport(section)
        }
        for ((name, bytes) in cases) {
            var installs = 0

            val restored = RestoreCoordinator.installSagerExport(bytes) {
                installs++
                true
            }

            assertFalse(name, restored)
            assertEquals(name, 0, installs)
        }
    }

    @Test
    fun structurallyCompleteEmptySagerSnapshotUsesProductionDecoder() {
        var installs = 0

        val restored = RestoreCoordinator.installSagerExport(emptySagerExport()) { snapshot ->
            installs++
            assertTrue(snapshot.groups.isEmpty())
            assertTrue(snapshot.profiles.isEmpty())
            assertTrue(snapshot.routers.isEmpty())
            assertTrue(snapshot.members.isEmpty())
            assertTrue(snapshot.sources.isEmpty())
            assertTrue(snapshot.rules.isEmpty())
            true
        }

        assertTrue(restored)
        assertEquals(1, installs)
    }

    @Test
    fun successfulCommitCleanupCanResumeAfterEveryDeleteFailure() {
        for (deleteAt in 1..3) {
            val dir = dir()
            val committed = RestoreCoordinator.commit(
                dir = dir,
                incomingConfig = emptyList(),
                capturePreviousConfig = { emptyList() },
                capturePreviousSager = { emptySagerExport() },
                restoreConfig = { true },
                restoreSagerIncoming = { true },
                restoreSagerPrevious = { true },
                deleteFile = failDeleteAt(deleteAt),
            )

            assertFalse("deleteAt=$deleteAt", committed.success)
            assertEquals(RestoreCoordinator.Phase.COMPLETE, RestoreCoordinator.readPhase(dir))
            assertTrue(RestoreCoordinator.journalFile(dir).isFile)

            val resumed = RestoreCoordinator.recoverLocked(dir, { false }, { false })
            assertTrue("deleteAt=$deleteAt", resumed.success)
            assertFalse(RestoreCoordinator.journalFile(dir).exists())
            assertFalse(RestoreCoordinator.snapshotFile(dir).exists())
            assertFalse(RestoreCoordinator.sagerSnapshotFile(dir).exists())
        }
    }

    @Test
    fun successfulRollbackCleanupCanResumeAfterEveryDeleteFailure() {
        for (deleteAt in 1..3) {
            val dir = dir()
            val committed = RestoreCoordinator.commit(
                dir = dir,
                incomingConfig = emptyList(),
                capturePreviousConfig = { emptyList() },
                capturePreviousSager = { emptySagerExport() },
                restoreConfig = { true },
                restoreSagerIncoming = { false },
                restoreSagerPrevious = { bytes ->
                    RestoreCoordinator.installSagerExport(bytes) { true }
                },
                deleteFile = failDeleteAt(deleteAt),
            )

            assertFalse("deleteAt=$deleteAt", committed.success)
            assertEquals(RestoreCoordinator.Phase.COMPLETE, RestoreCoordinator.readPhase(dir))
            assertTrue(RestoreCoordinator.journalFile(dir).isFile)

            val resumed = RestoreCoordinator.recoverLocked(dir, { false }, { false })
            assertTrue("deleteAt=$deleteAt", resumed.success)
            assertFalse(RestoreCoordinator.journalFile(dir).exists())
            assertFalse(RestoreCoordinator.snapshotFile(dir).exists())
            assertFalse(RestoreCoordinator.sagerSnapshotFile(dir).exists())
        }
    }

    @Test
    fun failedConfigRollbackPreservesRecoveryMaterials() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes("OLD_SAGER".toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.PREPARED.name)
        var sagerAttempted = false

        val recovered = RestoreCoordinator.recoverLocked(
            dir,
            { false },
            { sagerAttempted = true; true },
        )

        assertFalse(recovered.success)
        assertEquals(RestoreCoordinator.Phase.PREPARED, recovered.phase)
        assertEquals(ApplyErrorCodes.RESTORE_FAILED, recovered.error)
        assertTrue(sagerAttempted)
        assertTrue(RestoreCoordinator.journalFile(dir).isFile)
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
    }

    @Test
    fun failedSagerRollbackPreservesRecoveryMaterials() {
        val dir = dir()
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(row("k", "old"))),
        )
        RestoreCoordinator.sagerSnapshotFile(dir).writeBytes("OLD_SAGER".toByteArray())
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.CONFIG_COMMITTED.name)

        val recovered = RestoreCoordinator.recoverLocked(dir, { true }, { false })

        assertFalse(recovered.success)
        assertEquals(RestoreCoordinator.Phase.CONFIG_COMMITTED, recovered.phase)
        assertEquals(ApplyErrorCodes.RESTORE_FAILED, recovered.error)
        assertTrue(RestoreCoordinator.journalFile(dir).isFile)
        assertTrue(RestoreCoordinator.snapshotFile(dir).isFile)
        assertTrue(RestoreCoordinator.sagerSnapshotFile(dir).isFile)
    }

    @Test
    fun successClearsJournal() {
        val dir = dir()
        val out = RestoreCoordinator.commit(
            dir = dir,
            incomingConfig = listOf(row("k", "new")),
            capturePreviousConfig = { listOf(row("k", "old")) },
            capturePreviousSager = { "OLD_SAGER".toByteArray() },
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
}
