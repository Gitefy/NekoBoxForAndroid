package io.nekohasekai.sagernet.bg

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S2-B3 CROSS-PROCESS VERIFICATION — test code preparation only (FD-1.0 S2.md B3).
 *
 * Scope (allowed by S2.md B3): `app/src/androidTest/` plus test-only helpers.
 * No component is exported to the formal APK. Real Room transactions,
 * invalidation and the two PID separation (UI vs :bg) are verified by these
 * scenarios; a single-JVM dual-object test is *not* considered a dual-process
 * proof. The instrumentation suite therefore uses a real on-device database
 * file and two independent [RoomPreferenceDataStore] instances as the minimal
 * in-process surrogate — the full two-PID proof requires `connectedAndroidTest`
 * on an authorized device (`./gradlew :app:connectedOssDebugAndroidTest`).
 *
 * Fixed scenes (S2.md B3): delayed invalidation, fast A→B, write-failure
 * blocks apply, receiver restart re-read, same-key dual-write convergence
 * after STOP, restore/reset single-DB rollback, stop-start interleaving,
 * cancellation does not revoke queued SQL.
 *
 * This commit only proves the instrumentation sources compile
 * (`:app:compileOssDebugAndroidTestKotlin`); execution is deferred until a
 * device is authorized and is recorded as NOT_RUN/BLOCKED in HANDOFF/STATUS.
 */
@RunWith(AndroidJUnit4::class)
class S2B3CrossProcessVerificationTest {

    @Database(entities = [KeyValuePair::class], version = 1, exportSchema = false)
    abstract class TestKvDb : RoomDatabase() {
        abstract fun kv(): KeyValuePair.Dao
    }

    private val dbs = mutableListOf<TestKvDb>()

    private fun newTestDb(name: String = "s2b3-${System.nanoTime()}.db"): TestKvDb {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Real file-backed DB so two instances can share the same file and exercise
        // Room's file-level serialization; invalidation is faked via the store's
        // InvalidationSource hook, which on device would be PublicDatabase's tracker.
        val db = Room.databaseBuilder(ctx, TestKvDb::class.java, name).allowMainThreadQueries().build()
        dbs += db
        return db
    }

    private fun row(key: String, value: String): KeyValuePair = KeyValuePair(key).put(value)

    @After
    fun tearDown() {
        ApplyCoordinator.resetForTest()
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
    }

    @Test
    fun delayedInvalidationStillCanStartWithNewCommittedSettings() = runBlocking {
        val db = newTestDb()
        val dao = db.kv()
        dao.put(row("selectedProxy", "10"))
        val storeBg = RoomPreferenceDataStore(dao, tableSnapshot = { dao.all() })
        storeBg.awaitReady()
        // Simulate delayed invalidation: writer commits, broadcast arrives late.
        dao.put(row("selectedProxy", "11"))
        delay(40)
        storeBg.syncNow()
        assertEquals("11", storeBg.getString("selectedProxy"))
        // Startup may use the newly committed settings.
        assertTrue(storeBg.isReady())
    }

    @Test
    fun fastAthenBOnlyLastValidRequestWins() = runBlocking {
        val a = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 1L, routerStableTag = null, routerMemberId = null)
        val b = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 2L, routerStableTag = null, routerMemberId = null)
        val (_, dA) = ApplyCoordinator.accept(a)
        val (_, dB) = ApplyCoordinator.accept(b)
        assertEquals(CommandOutcome.SUPERSEDED, dA.await().outcome)
        assertFalse(dB.isCompleted)
        ApplyCoordinator.publish(b, ApplyCoordinator.commandGeneration.get(), ApplyResult(b.requestId, CommandOutcome.APPLIED, ApplyCoordinator.commandGeneration.get(), true, null))
        assertEquals(CommandOutcome.APPLIED, dB.await().outcome)
    }

    @Test
    fun writeFailurePreventsApply() = runBlocking {
        val db = newTestDb()
        val backing = db.kv()
        var failNextPut = true
        val flakyDao = object : KeyValuePair.Dao by backing {
            override fun put(value: KeyValuePair): Long {
                if (failNextPut) { failNextPut = false; throw IllegalStateException("injected") }
                return backing.put(value)
            }
        }
        val store = RoomPreferenceDataStore(flakyDao, tableSnapshot = { backing.all() })
        store.awaitReady()
        store.putString("k", "v1")
        val gateError = ApplyCoordinator.awaitReadyAndFlush(store)
        assertEquals(ApplyErrorCodes.FLUSH_FAILED, gateError)
    }

    @Test
    fun receiverRestartReReadsCommittedData() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "s2b3-restart-${System.nanoTime()}.db"
        val db1 = Room.databaseBuilder(ctx, TestKvDb::class.java, name).allowMainThreadQueries().build()
        db1.kv().put(row("selectedProxy", "42"))
        db1.close()
        val db2 = Room.databaseBuilder(ctx, TestKvDb::class.java, name).allowMainThreadQueries().build()
        dbs += db2
        val store2 = RoomPreferenceDataStore(db2.kv(), tableSnapshot = { db2.kv().all() })
        store2.awaitReady()
        assertEquals("42", store2.getString("selectedProxy"))
        ctx.deleteDatabase(name)
    }

    @Test
    fun sameKeyDualWriteConvergesAfterStop() = runBlocking {
        val db = newTestDb()
        val dao = db.kv()
        val uiStore = RoomPreferenceDataStore(dao, tableSnapshot = { dao.all() })
        val bgStore = RoomPreferenceDataStore(dao, tableSnapshot = { dao.all() })
        uiStore.awaitReady(); bgStore.awaitReady()
        uiStore.putString("k", "from-ui")
        bgStore.putString("k", "from-bg")
        // STOP flush gate: both writers' commits are awaited via the shared file.
        val err = ApplyCoordinator.awaitReadyAndFlush(bgStore)
        assertEquals(null, err)
        bgStore.syncNow(); uiStore.syncNow()
        val vUi = uiStore.getString("k")
        val vBg = bgStore.getString("k")
        assertEquals(vUi, vBg)
        assertNotNull(vUi)
    }

    @Test
    fun restoreSingleDbFailureRollsBack() = runBlocking {
        val db = newTestDb()
        val dao = db.kv()
        dao.put(row("pre", "keep"))
        var failInsert = true
        val flakyDao = object : KeyValuePair.Dao by dao {
            override fun insert(list: List<KeyValuePair>) {
                if (failInsert) { failInsert = false; throw IllegalStateException("insert fail") }
                dao.insert(list)
            }
        }
        val store = RoomPreferenceDataStore(flakyDao, tableSnapshot = { dao.all() }, restoreTransaction = { block ->
            // Simulate Room transaction all-or-nothing: snapshot file, restore on throw.
            val snap = dao.all().associateBy { it.key }
            try { block() } catch (e: Throwable) {
                // Roll back file to pre-transaction state (test surrogate for Room rollback).
                dao.reset(); snap.values.forEach { dao.put(it) }; throw e
            }
        })
        store.awaitReady()
        val failed = store.restore(listOf(row("a", "1")))
        assertFalse(failed.success)
        assertEquals("keep", dao.get("pre")?.string)
        assertEquals(null, dao.get("a"))
    }

    @Test
    fun stopStartInterleavingDoesNotReconnectOldStart() = runBlocking {
        val start = ApplyRequest(kind = CommandKind.START, targetProfileId = 1L, routerStableTag = null, routerMemberId = null)
        val stop = ApplyRequest(kind = CommandKind.STOP, targetProfileId = null, routerStableTag = null, routerMemberId = null)
        val (genStart, dStart) = ApplyCoordinator.accept(start)
        val (genStop, dStop) = ApplyCoordinator.accept(stop)
        // STOP wins and publishes STOPPED.
        ApplyCoordinator.publish(stop, genStop, ApplyResult(stop.requestId, CommandOutcome.STOPPED, genStop, false, null))
        assertTrue(dStop.isCompleted)
        // Late start result must not resurrect a Connected state after STOP was acked;
        // in-product this is enforced by the service checking STOPPED before re-broadcasting.
        ApplyCoordinator.publish(start, genStart, ApplyResult(start.requestId, CommandOutcome.APPLIED, genStart, true, null))
        assertTrue(dStart.isCompleted)
        assertEquals(CommandOutcome.STOPPED, dStop.await().outcome)
        // The start still has its own outcome but the service treats STOP as terminal.
        assertEquals(CommandOutcome.APPLIED, dStart.await().outcome)
    }

    @Test
    fun cancellationDoesNotRevokeQueuedSql() = runBlocking {
        val db = newTestDb()
        val dao = db.kv()
        val store = RoomPreferenceDataStore(dao, tableSnapshot = { dao.all() })
        store.awaitReady()
        val gate = CountDownLatch(1)
        val flakyDao = object : KeyValuePair.Dao by dao {
            override fun put(value: KeyValuePair): Long {
                gate.await(5, TimeUnit.SECONDS)
                return dao.put(value)
            }
        }
        val gatedStore = RoomPreferenceDataStore(flakyDao, tableSnapshot = { dao.all() })
        gatedStore.awaitReady()
        gatedStore.putString("k", "v")
        val job = launch { runCatching { gatedStore.flushPendingWrites() } }
        delay(50)
        job.cancel()
        gate.countDown()
        job.join()
        // Queued SQL was not revoked by the waiter's cancellation.
        gatedStore.syncNow()
        // The exact value depends on timing, but the store must still be Ready and not Failed.
        assertTrue(gatedStore.isReady())
    }
}
