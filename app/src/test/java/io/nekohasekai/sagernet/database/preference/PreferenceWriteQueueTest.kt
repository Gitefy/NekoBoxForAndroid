package io.nekohasekai.sagernet.database.preference

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-B3 write queue, barrier and failure protocol contract (FD-1.0,
 * S1-B3.md sections 3/4/6/7).
 *
 * Determinism: controllable fake DAO, explicit gates, futures and flush
 * barriers as rendezvous points. Timeouts only protect against hangs and
 * negative (must-not-finish) assertions; no sleep is used as an ordering
 * condition.
 */
class PreferenceWriteQueueTest {

    private fun row(key: String, value: String): KeyValuePair =
        KeyValuePair(key).put(value)

    /** Blocks the DAO call until released; timeout fails loudly instead of hanging. */
    private class Gate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun enter() {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("gate was not released in time")
            }
        }

        fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)

        fun release() {
            release.countDown()
        }
    }

    private class FakeKvDao : KeyValuePair.Dao {
        val tableLock = Any()
        val table = LinkedHashMap<String, KeyValuePair>()
        val callOrder = LinkedBlockingQueue<String>()
        val putGates = LinkedBlockingQueue<Gate>()
        val deleteGates = LinkedBlockingQueue<Gate>()
        val resetGates = LinkedBlockingQueue<Gate>()
        val insertGates = LinkedBlockingQueue<Gate>()
        val transactionAborts = AtomicInteger(0)
        @Volatile var failPuts = false
        @Volatile var failDeletes = false
        @Volatile var failResets = false
        @Volatile var failInserts = false

        fun tableSnapshot(): List<KeyValuePair> = synchronized(tableLock) {
            table.values.toList()
        }

        override fun all(): List<KeyValuePair> = tableSnapshot()

        override fun get(key: String): KeyValuePair? = synchronized(tableLock) { table[key] }

        override fun put(value: KeyValuePair): Long {
            callOrder.put("put:${value.key}")
            putGates.poll()?.enter()
            if (failPuts) throw IllegalStateException("injected put failure")
            synchronized(tableLock) { table[value.key] = value }
            return 1L
        }

        override fun delete(key: String): Int {
            callOrder.put("delete:$key")
            deleteGates.poll()?.enter()
            if (failDeletes) throw IllegalStateException("injected delete failure")
            synchronized(tableLock) { table.remove(key) }
            return 1
        }

        override fun reset(): Int {
            callOrder.put("reset")
            resetGates.poll()?.enter()
            if (failResets) throw IllegalStateException("injected reset failure")
            synchronized(tableLock) { table.clear() }
            return 0
        }

        override fun insert(list: List<KeyValuePair>) {
            callOrder.put("insert:${list.size}")
            insertGates.poll()?.enter()
            if (failInserts) throw IllegalStateException("injected insert failure")
            synchronized(tableLock) { list.forEach { table[it.key] = it } }
        }
    }

    /** Simulates a Room transaction: all-or-nothing around the DAO body. */
    private class FakeTransactionRunner(private val dao: FakeKvDao) : (() -> Unit) -> Unit {
        val invocations = AtomicInteger(0)

        override fun invoke(block: () -> Unit) {
            invocations.incrementAndGet()
            val snapshot = LinkedHashMap(dao.table)
            try {
                block()
            } catch (e: Throwable) {
                synchronized(dao.tableLock) {
                    dao.table.clear()
                    dao.table.putAll(snapshot)
                }
                dao.transactionAborts.incrementAndGet()
                throw e
            }
        }
    }

    private fun newStore(
        dao: FakeKvDao,
        runner: FakeTransactionRunner? = null,
    ): RoomPreferenceDataStore = RoomPreferenceDataStore(
        dao,
        tableSnapshot = { dao.tableSnapshot() },
        restoreTransaction = runner?.let { fake -> { block -> fake(block) } } ?: { block -> block() },
    )

    private class FlushOutcome {
        val result = AtomicReference<FlushResult?>(null)
        val error = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)

        fun awaitSuccess(seconds: Long = 5): FlushResult {
            assertTrue("flush did not finish in time", done.await(seconds, TimeUnit.SECONDS))
            assertNull("flush failed with ${error.get()}", error.get())
            return result.get() ?: throw AssertionError("flush produced no result")
        }

        /** Negative rendezvous: the flush must still be running. */
        fun assertStillWaiting(millis: Long = 150) {
            assertFalse("flush finished too early", done.await(millis, TimeUnit.MILLISECONDS))
        }
    }

    private fun flushAsync(store: RoomPreferenceDataStore): FlushOutcome {
        // Enqueue the marker synchronously on the calling thread so its cut is
        // ordered before any later puts the test admits. The await still runs
        // on a background thread so the test can perform rendezvous.
        val future = store.flushPendingWritesAsync()
        val outcome = FlushOutcome()
        thread(isDaemon = true) {
            try {
                outcome.result.set(future.get(10, TimeUnit.SECONDS))
            } catch (e: Throwable) {
                outcome.error.set(e)
            } finally {
                outcome.done.countDown()
            }
        }
        return outcome
    }

    // ---- barrier group -------------------------------------------------

    @Test
    fun barrierWaitsForPriorWrite() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        val gate = Gate()
        dao.putGates.put(gate)

        store.putString("k", "v1")
        val outcome = flushAsync(store)
        assertTrue("prior write never reached the DAO", gate.awaitEntered())
        outcome.assertStillWaiting()

        gate.release()
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertEquals(1, result.completed)
        assertTrue(result.failures.isEmpty())
        assertEquals("v1", dao.table["k"]?.string)
    }

    @Test
    fun barrierDoesNotWaitForLaterWrite() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        val firstGate = Gate()
        val laterGate = Gate()
        dao.putGates.put(firstGate)

        store.putString("k1", "a")
        val outcome = flushAsync(store)
        assertTrue(firstGate.awaitEntered())

        // Admitted after the barrier cut: must not extend or heal this barrier.
        dao.putGates.put(laterGate)
        store.putString("k2", "b")
        outcome.assertStillWaiting()

        firstGate.release()
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertEquals(1, result.completed)
        assertTrue(result.failures.isEmpty())

        laterGate.release()
        val finalFlush = runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertTrue(finalFlush.success)
        assertEquals("b", dao.table["k2"]?.string)
    }

    @Test
    fun barrierCapturedBeforeRecoveryStillFails() {
        val dao = FakeKvDao()
        val store = newStore(dao)

        dao.failPuts = true
        store.putString("k", "v1") // setter must not throw on persistence failure
        assertEquals("v1", store.getString("k")) // optimistic mirror kept

        val outcome = flushAsync(store)
        val failed = outcome.awaitSuccess()
        assertFalse(failed.success)
        val failure = failed.failures.single()
        assertEquals(WriteOperationKind.PUT, failure.operation)
        assertEquals("k", failure.key)
        assertNotNull(failure.reason)
        assertFalse(failure.reason!!.contains("v1")) // reasons must be sanitized

        // Recovery write admitted only after the failed barrier returned.
        dao.failPuts = false
        store.putString("k", "v2")
        val recovered = runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertTrue(recovered.success)
        assertTrue(recovered.failures.isEmpty())
        assertEquals("v2", dao.table["k"]?.string)
    }

    @Test
    fun barrierCapturedAfterRecoverySucceeds() {
        val dao = FakeKvDao()
        val store = newStore(dao)

        dao.failPuts = true
        store.putString("k", "v1")
        assertTrue(flushAsync(store).awaitSuccess().let { !it.success })

        dao.failPuts = false
        store.putString("k", "v2")
        runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }

        val outcome = flushAsync(store)
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun multipleWritesBarrierCompletesOnlyAfterAllPriorWrites() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        val firstGate = Gate()
        val secondGate = Gate()
        dao.putGates.put(firstGate)
        dao.putGates.put(secondGate)

        store.putString("k1", "a")
        store.putString("k2", "b")
        val outcome = flushAsync(store)
        assertTrue(firstGate.awaitEntered())
        outcome.assertStillWaiting()

        firstGate.release()
        assertTrue(secondGate.awaitEntered())
        outcome.assertStillWaiting()

        secondGate.release()
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertEquals(2, result.completed)
    }

    @Test
    fun deleteParticipatesInBarrier() {
        val dao = FakeKvDao()
        val store = newStore(dao)

        store.putString("k", "v")
        runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertEquals("v", dao.table["k"]?.string)

        val gate = Gate()
        dao.deleteGates.put(gate)
        store.remove("k")
        val outcome = flushAsync(store)
        assertTrue(gate.awaitEntered())
        outcome.assertStillWaiting()

        gate.release()
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertEquals(2, result.completed)
        assertNull(dao.table["k"])
        assertNull(store.getString("k"))
    }

    @Test
    fun repeatedFlushWhenCleanReturnsImmediately() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        store.putString("k", "v")
        runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }

        val clean = runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertTrue(clean.success)
        assertTrue(clean.failures.isEmpty())
    }

    // ---- failure group -------------------------------------------------

    @Test
    fun resetFailureAppearsInFlushResult() {
        val dao = FakeKvDao()
        val runner = FakeTransactionRunner(dao)
        val store = newStore(dao, runner)
        dao.table["k"] = row("k", "v")

        dao.failResets = true
        val failed = runBlocking { withTimeout(5_000) { store.reset() } }
        assertFalse(failed.success)
        val failure = failed.failures.single()
        assertEquals(WriteOperationKind.RESET, failure.operation)
        assertNull(failure.key)
        assertNotNull(failure.reason)
        assertEquals("v", dao.table["k"]?.string) // transaction rolled back

        dao.failResets = false
        val recovered = runBlocking { withTimeout(5_000) { store.reset() } }
        assertTrue(recovered.success)
        assertTrue(dao.table.isEmpty())
        assertTrue(runner.invocations.get() >= 2)
    }

    @Test
    fun restoreFailureAppearsInFlushResult() {
        val dao = FakeKvDao()
        val store = newStore(dao)

        dao.failInserts = true
        val failed = store.restore(listOf(row("a", "1")))
        assertFalse(failed.success)
        val failure = failed.failures.single()
        assertEquals(WriteOperationKind.RESTORE, failure.operation)
        assertNull(failure.key)
        assertNotNull(failure.reason)
        assertTrue(dao.table.isEmpty())

        dao.failInserts = false
        val recovered = store.restore(listOf(row("a", "1")))
        assertTrue(recovered.success)
        assertEquals("1", dao.table["a"]?.string)
    }

    @Test
    fun keyedSuccessDoesNotHealFailedWholeTableFence() {
        val dao = FakeKvDao()
        val store = newStore(dao)

        dao.failResets = true
        assertFalse(runBlocking { withTimeout(5_000) { store.reset() } }.success)
        dao.failResets = false

        store.putString("k", "v") // lands in the real (old) DB; cannot heal the fence
        val outcome = flushAsync(store)
        val result = outcome.awaitSuccess()
        assertFalse(result.success)
        val failure = result.failures.single()
        assertEquals(WriteOperationKind.RESET, failure.operation)
        assertNull(failure.key)
        assertEquals("v", dao.table["k"]?.string)
    }

    @Test
    fun laterSuccessfulWholeTableFenceRecoversEarlierFenceFailure() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        dao.table["pre"] = row("pre", "old")

        dao.failResets = true
        assertFalse(runBlocking { withTimeout(5_000) { store.reset() } }.success)
        dao.failResets = false

        store.putString("k", "v")
        assertFalse(flushAsync(store).awaitSuccess().success) // global failure still visible

        val healed = runBlocking { withTimeout(5_000) { store.reset() } }
        assertTrue(healed.success)
        assertTrue(dao.table.isEmpty())

        val outcome = flushAsync(store)
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertTrue(result.failures.isEmpty())
    }

    // ---- fence / integration group --------------------------------------

    @Test
    fun queuedWriteBeforeRestoreCannotCommitAfterRestoreWinner() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        val gate = Gate()
        dao.putGates.put(gate)

        store.putString("k", "v1")
        val restored = AtomicReference<FlushResult?>(null)
        val restoreDone = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                restored.set(store.restore(listOf(row("k", "restored"))))
            } finally {
                restoreDone.countDown()
            }
        }

        assertTrue(gate.awaitEntered())
        assertFalse(
            "restore returned while a pre-restore write was still blocked",
            restoreDone.await(150, TimeUnit.MILLISECONDS),
        )

        gate.release()
        assertTrue(restoreDone.await(5, TimeUnit.SECONDS))
        assertTrue(restored.get()!!.success)
        assertEquals("restored", dao.table["k"]?.string)
        assertNull(dao.table.entries.firstOrNull { it.key == "k" && it.value.string == "v1" })
    }

    @Test
    fun restoreReplacementIsAtomicOnInsertFailure() {
        val dao = FakeKvDao()
        val runner = FakeTransactionRunner(dao)
        val store = newStore(dao, runner)
        dao.table["pre"] = row("pre", "old")

        dao.failInserts = true
        val failed = store.restore(listOf(row("a", "1")))
        assertFalse(failed.success)
        assertEquals("old", dao.table["pre"]?.string)
        assertNull(dao.table["a"])
        assertTrue(dao.transactionAborts.get() >= 1)
        assertTrue(runner.invocations.get() >= 1)

        dao.failInserts = false
        assertTrue(store.restore(listOf(row("a", "1"))).success)
        assertEquals("1", dao.table["a"]?.string)
    }

    @Test
    fun restoreUsesConfiguredTransactionRunner() {
        val dao = FakeKvDao()
        val runner = FakeTransactionRunner(dao)
        val store = newStore(dao, runner)

        assertTrue(store.restore(listOf(row("a", "1"))).success)
        assertTrue(runner.invocations.get() >= 1)

        assertTrue(runBlocking { withTimeout(5_000) { store.reset() } }.success)
        assertTrue(runner.invocations.get() >= 2)
    }

    @Test
    fun resetAwaitReturnsOnlyAfterDurableTerminal() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        dao.table["k"] = row("k", "v")
        val gate = Gate()
        dao.resetGates.put(gate)

        val outcome = FlushOutcome()
        thread(isDaemon = true) {
            try {
                runBlocking { outcome.result.set(store.reset()) }
            } catch (e: Throwable) {
                outcome.error.set(e)
            } finally {
                outcome.done.countDown()
            }
        }
        assertTrue(gate.awaitEntered())
        outcome.assertStillWaiting()

        gate.release()
        val result = outcome.awaitSuccess()
        assertTrue(result.success)
        assertTrue(dao.table.isEmpty())
    }

    @Test
    fun failedRestoreRealignsMirrorAndKeepsPostFencePendingValues() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        dao.table["pre"] = row("pre", "old")

        val resetGate = Gate()
        dao.resetGates.put(resetGate)
        dao.failResets = true

        val outcome = FlushOutcome()
        thread(isDaemon = true) {
            try {
                runBlocking { outcome.result.set(store.reset()) }
            } catch (e: Throwable) {
                outcome.error.set(e)
            } finally {
                outcome.done.countDown()
            }
        }
        assertTrue(resetGate.awaitEntered())

        // Post-fence keyed write admitted while the fence is in flight; its
        // writer task is queued behind the whole-table task.
        val putGate = Gate()
        dao.putGates.put(putGate)
        store.putString("k", "v")

        resetGate.release()
        val failed = outcome.awaitSuccess()
        assertFalse(failed.success)
        assertEquals("old", store.getString("pre")) // realigned to the actual table
        assertEquals("old", dao.table["pre"]?.string)

        putGate.release()
        runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertEquals("v", dao.table["k"]?.string)
        assertEquals("v", store.getString("k"))
    }

    @Test
    fun listenerReentrancyPreservesAdmissionOrder() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        store.registerChangeListener(object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(
                changedStore: androidx.preference.PreferenceDataStore,
                key: String,
            ) {
                if (key == "k1") (changedStore as RoomPreferenceDataStore).putString("k2", "b")
            }
        })

        store.putString("k1", "a")
        val result = runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertTrue(result.success)
        assertEquals(2, result.completed)
        assertEquals(listOf("put:k1", "put:k2"), dao.callOrder.toList())
        assertEquals("a", dao.table["k1"]?.string)
        assertEquals("b", dao.table["k2"]?.string)
    }

    @Test
    fun waiterCancellationDoesNotCancelWrites() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        val gate = Gate()
        dao.putGates.put(gate)

        store.putString("k", "v")
        val cancelled = runBlocking {
            val job = launch(Dispatchers.IO) { store.flushPendingWrites() }
            assertTrue(gate.awaitEntered())
            job.cancelAndJoinWaiting()
        }
        assertTrue(cancelled)

        gate.release()
        val result = runBlocking { withTimeout(5_000) { store.flushPendingWrites() } }
        assertTrue(result.success)
        assertEquals("v", dao.table["k"]?.string)
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinWaiting(): Boolean {
        cancel()
        try {
            join()
        } catch (e: CancellationException) {
            // The cancelled waiter itself rethrows; a cancelled child joining is fine.
        }
        return true
    }

    @Test
    fun restoreInputRowsAreFrozenAtAdmission() {
        val dao = FakeKvDao()
        val store = newStore(dao)
        val rows = arrayListOf(row("k", "orig"))
        val gate = Gate()
        dao.insertGates.put(gate)

        val restored = AtomicReference<FlushResult?>(null)
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                restored.set(store.restore(rows))
            } finally {
                done.countDown()
            }
        }
        assertTrue(gate.awaitEntered())

        // Mutate the caller-owned list and the mutable KeyValuePair after admission.
        rows[0].value = "hacked".toByteArray()
        rows[0] = row("k", "hacked2")
        gate.release()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(restored.get()!!.success)
        assertEquals("orig", dao.table["k"]?.string)
        assertEquals("orig", store.getString("k"))
    }
}
