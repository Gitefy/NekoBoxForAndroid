package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartHandoffTest {
    @Test
    fun connectingStartWaitsForOldCleanupInsteadOfReportingApplied() = runBlocking {
        ApplyCoordinator.resetForTest()
        val (_, oldResult) = ApplyCoordinator.accept(ApplyRequest("old", CommandKind.START, 1L, null, null))
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    allowCleanup.await()
                    events += "old closed"
                }
            }
        }
        val (_, newResult) = ApplyCoordinator.accept(ApplyRequest("new", CommandKind.START, 2L, null, null))
        val handoff = CompletableDeferred<Unit>()
        try {
            dispatchStart(
                BaseService.State.Connecting,
                start = { error("old startup must be joined first") },
                restart = {
                    launch {
                        oldJob.cancelAndJoin()
                        events += "new started"
                        handoff.complete(Unit)
                    }
                },
                applied = { error("connecting is not an applied runtime") },
                failed = { error("a connecting START must hand off") },
            )
            cleanupStarted.await()
            assertEquals(CommandOutcome.SUPERSEDED, oldResult.await().outcome)
            assertFalse(newResult.isCompleted)
            assertFalse(handoff.isCompleted)
            assertTrue(events.isEmpty())
            allowCleanup.complete(Unit)
            handoff.await()
            assertEquals(listOf("old closed", "new started"), events)
        } finally {
            allowCleanup.complete(Unit)
            oldJob.cancelAndJoin()
            ApplyCoordinator.resetForTest()
        }

        for (state in BaseService.State.values()) {
            val actions = mutableListOf<String>()
            dispatchStart(state, { actions += "start" }, { actions += "restart" },
                { actions += "applied" }, { actions += "failed" })
            val expected = when (state) {
                BaseService.State.Stopped -> "start"
                BaseService.State.Connecting -> "restart"
                BaseService.State.Connected -> "applied"
                else -> "failed"
            }
            assertEquals(listOf(expected), actions)
        }
    }
}
