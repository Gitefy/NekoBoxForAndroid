package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

object ApplyCommandWaiter {
    private val lock = Any()
    private val pending = LinkedHashMap<String, CompletableDeferred<CommandOutcome>>()

    fun resetForTest() {
        synchronized(lock) { pending.clear() }
    }

    fun register(requestId: String): CompletableDeferred<CommandOutcome> {
        val deferred = CompletableDeferred<CommandOutcome>()
        synchronized(lock) { pending[requestId] = deferred }
        return deferred
    }

    fun onCommandResult(requestId: String, outcome: CommandOutcome) {
        val deferred = synchronized(lock) { pending.remove(requestId) } ?: return
        deferred.complete(outcome)
    }

    fun failAll() {
        val waiters = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        waiters.forEach { waiter ->
            if (!waiter.isCompleted) waiter.complete(CommandOutcome.FAILED)
        }
    }

    suspend fun await(
        requestId: String,
        deferred: CompletableDeferred<CommandOutcome>,
        timeoutMs: Long = ApplyCoordinator.OBSERVE_TIMEOUT_MS,
    ): CommandOutcome {
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        synchronized(lock) { pending.remove(requestId) }
        return result ?: CommandOutcome.FAILED
    }
}
