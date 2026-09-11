package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Global apply-command sequencer. Generation is a single monotonic counter
 * across START/RELOAD/STOP; a newer intent supersedes any older pending
 * command of any kind. The deferred is test-local only — production UI/:bg
 * observe [io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback.commandResult].
 */
object ApplyCoordinator {

    val commandGeneration = AtomicLong(0)

    private val lock = Any()
    private var pending: PendingRequest? = null

    private class PendingRequest(
        val requestId: String,
        val generation: Long,
        val deferred: CompletableDeferred<ApplyResult>,
    )

    fun resetForTest() {
        synchronized(lock) { pending = null }
        commandGeneration.set(0)
    }

    fun pendingCount(): Int = synchronized(lock) { if (pending == null) 0 else 1 }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        pending?.generation == generation
    }

    fun accept(request: ApplyRequest): Pair<Long, CompletableDeferred<ApplyResult>> {
        val generation = commandGeneration.incrementAndGet()
        val deferred = CompletableDeferred<ApplyResult>()
        val previous = synchronized(lock) {
            val old = pending
            pending = PendingRequest(request.requestId, generation, deferred)
            old
        }
        previous?.deferred?.let {
            if (!it.isCompleted) {
                it.complete(
                    ApplyResult(
                        requestId = previous.requestId,
                        outcome = CommandOutcome.SUPERSEDED,
                        instanceGeneration = previous.generation,
                        persisted = false,
                        errorCode = null,
                    ),
                )
            }
        }
        return generation to deferred
    }

    fun publish(request: ApplyRequest, generation: Long, result: ApplyResult) {
        val toComplete = synchronized(lock) {
            val current = pending ?: return
            if (current.generation != generation) return
            pending = null
            current
        }
        if (!toComplete.deferred.isCompleted) {
            toComplete.deferred.complete(result.copy(requestId = request.requestId))
        }
    }

    suspend fun awaitResult(
        request: ApplyRequest,
        deferred: CompletableDeferred<ApplyResult>,
        generation: Long,
        timeoutMs: Long = OBSERVE_TIMEOUT_MS,
    ): ApplyResult {
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        return result ?: ApplyResult(
            requestId = request.requestId,
            outcome = CommandOutcome.FAILED,
            instanceGeneration = generation,
            persisted = false,
            errorCode = ApplyErrorCodes.TIMEOUT,
        )
    }

    suspend fun awaitReadyAndFlush(store: RoomPreferenceDataStore): String? {
        when (val readiness = store.awaitReady()) {
            is RoomPreferenceDataStore.StoreReadiness.Ready -> {}
            is RoomPreferenceDataStore.StoreReadiness.Failed ->
                return ApplyErrorCodes.NOT_READY
            else -> return ApplyErrorCodes.NOT_READY
        }
        val flush = store.flushPendingWrites()
        if (!flush.success) {
            Logs.w { "apply gate: flush failed: ${flush.failures.firstOrNull()?.reason}" }
            return ApplyErrorCodes.FLUSH_FAILED
        }
        return null
    }

    const val OBSERVE_TIMEOUT_MS = 30_000L
}
