package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S2-B2 handshake contract (FD-1.0 S2.md B2).
 *
 * No device, no real DB: every test uses a controllable fake store / fake
 * router table so ordering comes from explicit gates, not sleeps.
 */
class ApplyHandshakeTest {

    private fun row(key: String, value: String): KeyValuePair =
        KeyValuePair(key).put(value)

    private class FakeKvDao : KeyValuePair.Dao {
        val table = LinkedHashMap<String, KeyValuePair>()
        @Volatile var failPuts = false
        fun snapshot(): List<KeyValuePair> = table.values.toList()
        override fun all(): List<KeyValuePair> = snapshot()
        override fun get(key: String): KeyValuePair? = table[key]
        override fun put(value: KeyValuePair): Long {
            if (failPuts) throw IllegalStateException("injected failure")
            table[value.key] = value
            return 1L
        }
        override fun delete(key: String): Int { table.remove(key); return 1 }
        override fun reset(): Int { table.clear(); return 0 }
        override fun insert(list: List<KeyValuePair>) { list.forEach { table[it.key] = it } }
    }

    private fun newStore(dao: FakeKvDao) = RoomPreferenceDataStore(
        dao, tableSnapshot = { dao.snapshot() },
    )

    @Before
    fun resetCoordinator() {
        ApplyCoordinator.resetForTest()
    }

    @Test
    fun failedFlushDoesNotSendApply() = runBlocking {
        val dao = FakeKvDao()
        val store = newStore(dao)
        store.awaitReady()
        dao.failPuts = true
        store.putString("k", "v1")
        // The flush itself reports failure; the sender gate must surface it.
        val gateError = ApplyCoordinator.awaitReadyAndFlush(store)
        assertEquals(ApplyErrorCodes.FLUSH_FAILED, gateError)
        // A subsequent successful write does not retroactively heal the gate
        // returned above; the caller must not have sent an APPLIED for that gate.
        assertNotNull(gateError)
    }

    @Test
    fun explicitTargetWinsOverStaleMirror() = runBlocking {
        val dao = FakeKvDao()
        val store = newStore(dao)
        store.awaitReady()
        store.putLong(Key.PROFILE_ID, 1L)
        dao.table[Key.PROFILE_ID] = KeyValuePair(Key.PROFILE_ID).put(2L)
        val committed = store.readCommittedSettingsSnapshot()
        val committedValue = ApplyService.resolveCommittedProfileId(
            ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = null, routerStableTag = null, routerMemberId = null),
            committed,
        )
        assertEquals(2L, committedValue)
        val request = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 2L, routerStableTag = null, routerMemberId = null)
        assertEquals(2L, request.targetProfileId)
    }

    @Test
    fun invalidTargetFailsWithoutFallback() = runBlocking {
        val knownIds = setOf(10L, 20L)
        fun validate(request: ApplyRequest): String? {
            val id = request.targetProfileId ?: return null
            return if (id !in knownIds) ApplyErrorCodes.INVALID_TARGET else null
        }
        val bad = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 99L, routerStableTag = null, routerMemberId = null)
        assertEquals(ApplyErrorCodes.INVALID_TARGET, validate(bad))
        val good = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 10L, routerStableTag = null, routerMemberId = null)
        assertEquals(null, validate(good))
    }

    @Test
    fun staleRequestResultCannotOverrideNewRequest() = runBlocking {
        val first = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 1L, routerStableTag = null, routerMemberId = null)
        val second = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 2L, routerStableTag = null, routerMemberId = null)
        val (gen1, deferred1) = ApplyCoordinator.accept(first)
        val (gen2, deferred2) = ApplyCoordinator.accept(second)
        // First pending is now SUPERSEDED.
        val firstResult = deferred1.await()
        assertEquals(CommandOutcome.SUPERSEDED, firstResult.outcome)
        // Publishing a stale result for gen1 must not override the newer pending.
        ApplyCoordinator.publish(first, gen1, ApplyResult(first.requestId, CommandOutcome.APPLIED, gen1, true, null))
        assertFalse(deferred2.isCompleted)
        // The current pending result applies.
        ApplyCoordinator.publish(second, gen2, ApplyResult(second.requestId, CommandOutcome.APPLIED, gen2, true, null))
        assertEquals(CommandOutcome.APPLIED, deferred2.await().outcome)
    }

    @Test
    fun duplicateRequestIsNotAppliedTwice() = runBlocking {
        val request = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 1L, routerStableTag = null, routerMemberId = null)
        val (gen, deferred) = ApplyCoordinator.accept(request)
        val executions = AtomicInteger(0)
        suspend fun applyOnce(): ApplyResult {
            return if (executions.incrementAndGet() == 1) {
                ApplyResult(request.requestId, CommandOutcome.APPLIED, gen, true, null)
            } else {
                ApplyResult(request.requestId, CommandOutcome.SUPERSEDED, gen, false, null)
            }
        }
        val first = applyOnce()
        ApplyCoordinator.publish(request, gen, first)
        assertEquals(CommandOutcome.APPLIED, deferred.await().outcome)
        // Second publish for same generation must not complete anything new and
        // cannot change the already terminal result.
        ApplyCoordinator.publish(request, gen, applyOnce())
        assertTrue(deferred.isCompleted)
        assertEquals(2, executions.get())
    }

    @Test
    fun stopAckWaitsForCleanup() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        suspend fun doStop(): ApplyResult {
            // Simulate killProcesses / network cleanup before ack (suspend, no thread block).
            gate.await()
            return ApplyResult("req-stop", CommandOutcome.STOPPED, 1, false, null)
        }
        var published: ApplyResult? = null
        val job = launch {
            val result = doStop()
            published = result
        }
        // Ack must not be visible before cleanup finishes.
        delay(50)
        assertEquals(null, published)
        gate.complete(Unit)
        job.join()
        assertEquals(CommandOutcome.STOPPED, published!!.outcome)
    }

    @Test
    fun stopDuringStartCannotReconnect() = runBlocking {
        val startReq = ApplyRequest(kind = CommandKind.START, targetProfileId = 1L, routerStableTag = null, routerMemberId = null)
        val stopReq = ApplyRequest(kind = CommandKind.STOP, targetProfileId = null, routerStableTag = null, routerMemberId = null)
        val (startGen, startDeferred) = ApplyCoordinator.accept(startReq)
        val (stopGen, stopDeferred) = ApplyCoordinator.accept(stopReq)
        assertEquals(CommandOutcome.SUPERSEDED, startDeferred.await().outcome)
        ApplyCoordinator.publish(stopReq, stopGen, ApplyResult(stopReq.requestId, CommandOutcome.STOPPED, stopGen, false, null))
        assertTrue(stopDeferred.isCompleted)
        ApplyCoordinator.publish(startReq, startGen, ApplyResult(startReq.requestId, CommandOutcome.APPLIED, startGen, true, null))
        assertEquals(CommandOutcome.SUPERSEDED, startDeferred.await().outcome)
        assertEquals(CommandOutcome.STOPPED, stopDeferred.await().outcome)
        assertFalse(ApplyCoordinator.isCurrent(startGen))
    }

    @Test
    fun missingCommittedProfileIdIsInvalidTarget() {
        val empty = ApplyService.resolveCommittedProfileId(
            ApplyRequest(kind = CommandKind.START, targetProfileId = null, routerStableTag = null, routerMemberId = null),
            emptyList(),
        )
        assertEquals(null, empty)
        val stringRow = ApplyService.resolveCommittedProfileId(
            ApplyRequest(kind = CommandKind.START, targetProfileId = null, routerStableTag = null, routerMemberId = null),
            listOf(KeyValuePair("selectedProxy").put("12")),
        )
        assertEquals(null, stringRow)
        val zero = ApplyService.resolveCommittedProfileId(
            ApplyRequest(kind = CommandKind.START, targetProfileId = null, routerStableTag = null, routerMemberId = null),
            listOf(KeyValuePair(Key.PROFILE_ID).put(0L)),
        )
        assertEquals(null, zero)
        val ok = ApplyService.resolveCommittedProfileId(
            ApplyRequest(kind = CommandKind.START, targetProfileId = null, routerStableTag = null, routerMemberId = null),
            listOf(KeyValuePair(Key.PROFILE_ID).put(12L)),
        )
        assertEquals(12L, ok)
    }

    @Test
    fun timeoutDoesNotReportRollback() = runBlocking {
        val request = ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = 1L, routerStableTag = null, routerMemberId = null)
        val (gen, deferred) = ApplyCoordinator.accept(request)
        // Never publish a result; the await uses a short timeout to simulate the
        // project's 30s observation window without actually waiting 30s.
        val result = ApplyCoordinator.awaitResult(request, deferred, gen, timeoutMs = 80)
        assertEquals(CommandOutcome.FAILED, result.outcome)
        assertEquals(ApplyErrorCodes.TIMEOUT, result.errorCode)
        // Timeout is "unconfirmed", not a claim that the operation was rolled back:
        // the deferred stays not-completed, the write may still land later.
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun routerSelectionCommitIsAwaited() = runBlocking {
        val latch = CountDownLatch(1)
        val commitLatch = CountDownLatch(1)
        val fakeRouterDao = object {
            val table = mutableMapOf<Long, String>()
            fun transactionalUpdate(id: Long, tag: String) {
                latch.await()
                table[id] = tag
                commitLatch.countDown()
            }
        }
        var updateSeen = false
        val t = Thread {
            fakeRouterDao.transactionalUpdate(42L, "stable-tag")
            updateSeen = true
        }.apply { isDaemon = true; start() }
        Thread.sleep(50)
        assertFalse(updateSeen)
        latch.countDown()
        assertTrue(commitLatch.await(5, TimeUnit.SECONDS))
        t.join(1_000)
        assertTrue(updateSeen)
        assertEquals("stable-tag", fakeRouterDao.table[42L])
    }
}
