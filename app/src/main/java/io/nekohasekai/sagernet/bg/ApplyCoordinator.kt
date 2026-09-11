package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * S2-B2 apply/stop handshake coordinator.
 *
 * Contract (FD-1.0 S2.md B2):
 * - explicit request target wins over the (possibly stale) optimistic mirror;
 * - the receiving side awaits its own previous settings writes (flush) before
 *   reading a fresh committed snapshot; a failed flush never reports APPLIED;
 * - each request maps to at most one terminal ApplyResult; a newer request
 *   supersedes older pending ones (SUPERSEDED), and late stale results cannot
 *   override newer intents;
 * - STOP ack is published only after the service confirms cleanup;
 * - the caller-side wait is bounded (30s project observation timeout); timing
 *   out is reported as "result unconfirmed" (FAILED+TIMEOUT at the caller) and
 *   never as an automatic rollback.
 *
 * The coordinator itself is UI/service-agnostic and fully testable on the JVM:
 * the core "apply" work is injected as a suspend lambda.
 */
object ApplyCoordinator {

    /** Per-service-instance generation: increments on every accepted request. */
    val commandGeneration = AtomicLong(0)

    /** Latest accepted request per kind; older pending futures get SUPERSEDED. */
    private val pendingByKind = ConcurrentHashMap<CommandKind, PendingRequest>()

    private class PendingRequest(
        val requestId: String,
        val generation: Long,
        val deferred: CompletableDeferred<ApplyResult>,
    )

    /**
     * Accept a request: assigns the in-instance command generation, supersedes
     * the previous pending request of the same kind, and returns the deferred
     * that exactly one terminal result will complete.
     */
    /** For tests: reset all state. */
    fun resetForTest() {
        pendingByKind.clear()
        commandGeneration.set(0)
    }

    fun pendingCount(): Int = pendingByKind.size

    fun accept(request: ApplyRequest): Pair<Long, CompletableDeferred<ApplyResult>> {
        val generation = commandGeneration.incrementAndGet()
        val deferred = CompletableDeferred<ApplyResult>()
        val previous = pendingByKind.put(request.kind, PendingRequest(request.requestId, generation, deferred))
        // Previous pending of same kind is superseded immediately; its requestId is preserved.
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

    /**
     * Publish the terminal result for [request]. Only the currently pending
     * generation of that kind may complete its deferred; a late result from a
     * superseded generation completes nothing (its deferred was already
     * resolved with SUPERSEDED at supersede time) and cannot override a newer
     * pending request.
     */
    fun publish(request: ApplyRequest, generation: Long, result: ApplyResult) {
        val pending = pendingByKind[request.kind] ?: return
        if (pending.generation != generation) return // stale, ignore silently
        pending.deferred.complete(result)
        pendingByKind.remove(request.kind, pending)
    }

    /**
     * Caller-side bounded wait. Returns the terminal result, or
     * FAILED+TIMEOUT when the 30s observation window elapsed (never a claim of
     * rollback; the request may still land later).
     */
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

    /**
     * Readiness + flush gate shared by all command kinds: the receiving side
     * must be Ready and its own queued settings writes must reach a durable
     * terminal state BEFORE any committed snapshot is read or any core action
     * is taken. A failed flush must not send "applied".
     */
    suspend fun awaitReadyAndFlush(store: RoomPreferenceDataStore): String? {
        when (val readiness = store.awaitReady()) {
            is RoomPreferenceDataStore.StoreReadiness.Ready -> {}
            is RoomPreferenceDataStore.StoreReadiness.Failed ->
                return readiness.errorCode.let { ApplyErrorCodes.NOT_READY }
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
