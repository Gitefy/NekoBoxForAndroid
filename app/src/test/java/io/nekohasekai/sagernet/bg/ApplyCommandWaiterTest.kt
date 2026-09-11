package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApplyCommandWaiterTest {
    @Before
    fun reset() {
        ApplyCommandWaiter.resetForTest()
    }

    @Test
    fun reloadIsNotSuccessfulBeforeMatchingCommandResult() = runBlocking {
        val request = RequestReloadAck.newFullReloadRequest()
        var sent = false
        val pending = async {
            RequestReloadAck.awaitApplied(
                request = request,
                send = { sent = true },
                timeoutMs = 2_000L,
            )
        }
        yield()
        assertTrue(sent)
        assertFalse(pending.isCompleted)
        ApplyCommandWaiter.onCommandResult(request.requestId, CommandOutcome.APPLIED)
        assertTrue(pending.await())
    }

    @Test
    fun matchingAppliedResultCompletesSuccess() = runBlocking {
        val request = RequestReloadAck.newFullReloadRequest()
        val pending = async {
            RequestReloadAck.awaitApplied(
                request = request,
                send = {},
                timeoutMs = 2_000L,
            )
        }
        yield()
        ApplyCommandWaiter.onCommandResult(request.requestId, CommandOutcome.APPLIED)
        assertTrue(pending.await())
    }

    @Test
    fun failedResultReturnsReloadFailure() = runBlocking {
        val request = RequestReloadAck.newFullReloadRequest()
        val pending = async {
            RequestReloadAck.awaitApplied(
                request = request,
                send = {},
                timeoutMs = 2_000L,
            )
        }
        yield()
        ApplyCommandWaiter.onCommandResult(request.requestId, CommandOutcome.FAILED)
        assertFalse(pending.await())
    }

    @Test
    fun supersededResultReturnsReloadFailure() = runBlocking {
        val request = RequestReloadAck.newFullReloadRequest()
        val pending = async {
            RequestReloadAck.awaitApplied(
                request = request,
                send = {},
                timeoutMs = 2_000L,
            )
        }
        yield()
        ApplyCommandWaiter.onCommandResult(request.requestId, CommandOutcome.SUPERSEDED)
        assertFalse(pending.await())
    }

    @Test
    fun unrelatedRequestIdDoesNotCompleteWaiter() = runBlocking {
        val request = RequestReloadAck.newFullReloadRequest()
        val pending = async {
            RequestReloadAck.awaitApplied(
                request = request,
                send = {},
                timeoutMs = 2_000L,
            )
        }
        yield()
        ApplyCommandWaiter.onCommandResult("other-id", CommandOutcome.APPLIED)
        assertFalse(pending.isCompleted)
        ApplyCommandWaiter.onCommandResult(request.requestId, CommandOutcome.APPLIED)
        assertTrue(pending.await())
    }

    @Test
    fun timeoutKeepsPersistedRule() = runBlocking {
        var persisted = false
        val result = io.nekohasekai.sagernet.database.RequestRuleApply.saveAndApply(
            persist = { persisted = true; true },
            reload = {
                RequestReloadAck.awaitApplied(
                    request = RequestReloadAck.newFullReloadRequest(),
                    send = {},
                    timeoutMs = 20L,
                )
            },
        )
        assertTrue(persisted)
        assertTrue(result.persisted)
        assertEquals(io.nekohasekai.sagernet.database.RequestRuleApply.Outcome.RELOAD_FAILED, result.outcome)
    }
}
