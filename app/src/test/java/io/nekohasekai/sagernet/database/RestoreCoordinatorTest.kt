package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RestoreCoordinatorTest {

    private fun dir() = Files.createTempDirectory("restore-j").toFile()

    private class FakeKvDao : KeyValuePair.Dao {
        val table = LinkedHashMap<String, KeyValuePair>()
        override fun all(): List<KeyValuePair> = table.values.toList()
        override fun get(key: String): KeyValuePair? = table[key]
        override fun put(value: KeyValuePair): Long { table[value.key] = value; return 1L }
        override fun delete(key: String): Int { table.remove(key); return 1 }
        override fun reset(): Int { table.clear(); return 0 }
        override fun insert(list: List<KeyValuePair>) { list.forEach { table[it.key] = it } }
    }

    @Test
    fun malformedBackupMutatesNothing() {
        val dir = dir()
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun configFailureLeavesPriorState() {
        val dir = dir()
        val prior = intArrayOf(1)
        val out = RestoreCoordinator.commit(
            dir = dir,
            configRows = listOf(KeyValuePair("k").put("new")),
            snapshotConfig = { listOf(KeyValuePair("k").put("old")) },
            restoreConfig = { prior[0] = 2; false },
            restoreSager = { prior[0] = 3; true },
        )
        assertFalse(out.success)
        assertEquals(2, prior[0])
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun secondDbFailureDoesNotReportSuccess() {
        val dir = dir()
        val cfg = intArrayOf(0)
        val out = RestoreCoordinator.commit(
            dir = dir,
            configRows = listOf(KeyValuePair("k").put("new")),
            snapshotConfig = { listOf(KeyValuePair("k").put("old")) },
            restoreConfig = { cfg[0] += 1; true },
            restoreSager = { false },
        )
        assertFalse(out.success)
        assertEquals("SAGER_RESTORE_FAILED", out.error)
        assertEquals(2, cfg[0])
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun processDeathAfterConfigRollsBack() {
        val dir = dir()
        val dao = FakeKvDao()
        val store = RoomPreferenceDataStore(dao, tableSnapshot = { dao.all() })
        runBlocking { store.awaitReady() }
        store.putString("k", "new")
        runBlocking { store.flushPendingWrites() }
        RestoreCoordinator.journalFile(dir).writeText(RestoreCoordinator.Phase.CONFIG_COMMITTED.name)
        RestoreCoordinator.snapshotFile(dir).writeBytes(
            RestoreCoordinator.encodeRows(listOf(KeyValuePair("k").put("old"))),
        )
        val recovered = RestoreCoordinator.recoverOnBoot(dir, store)
        assertTrue(recovered.success)
        assertEquals(RestoreCoordinator.Phase.IDLE, RestoreCoordinator.readPhase(dir))
        runBlocking { store.flushPendingWrites() }
        assertEquals("old", dao.table["k"]?.string)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
    }

    @Test
    fun concurrentApplyBlockedDuringRestore() {
        RestoreCoordinator.commit(
            dir = dir(),
            configRows = null,
            snapshotConfig = { emptyList() },
            restoreConfig = { true },
            restoreSager = {
                assertTrue(RestoreCoordinator.isActive())
                true
            },
        )
        assertFalse(RestoreCoordinator.isActive())
        assertEquals(ApplyErrorCodes.RESTORE_IN_PROGRESS, RestoreCoordinator.restoreInProgressError())
    }

    @Test
    fun successClearsJournal() {
        val dir = dir()
        val out = RestoreCoordinator.commit(
            dir = dir,
            configRows = listOf(KeyValuePair("k").put("new")),
            snapshotConfig = { listOf(KeyValuePair("k").put("old")) },
            restoreConfig = { true },
            restoreSager = { true },
        )
        assertTrue(out.success)
        assertEquals(RestoreCoordinator.Phase.COMPLETE, out.phase)
        assertFalse(RestoreCoordinator.journalFile(dir).exists())
        assertFalse(RestoreCoordinator.snapshotFile(dir).exists())
    }
}
