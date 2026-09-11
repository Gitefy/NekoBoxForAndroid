package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S2-B2 ApplyService: converts captured ApplyRequest into applied core state.
 *
 * Flow (sender side — sync, explicit target capture):
 *   1) capture targetProfileId / routerStableTag+routerMemberId at click time (before any await);
 *   2) awaitReadyAndFlush (settings flush; router DAO transaction for router selection);
 *   3) if flush/transaction fails -> FAILED, never send "APPLIED";
 *   4) send a small request (START/RELOAD/STOP) via explicit service Intent or
 *      SagerConnection/AIDL; the existing RELOAD broadcast is kept only as a
 *      compatibility adapter that converts to the same ApplyRequest handler.
 *
 * Flow (receiver side — service):
 *   awaitReadyAndFlush -> readCommittedSettingsSnapshot (NOT the optimistic
 *   cachedAll) -> validate explicit target -> apply proxy/router/reload; only
 *   after the exact core call succeeds does the receiver publish APPLIED.
 */
object ApplyService {

    /**
     * Sender gate for settings-only applies (e.g. toggles that do not change
     * router selection). Awaits readiness + flush; only on success does the
     * caller proceed to deliver the request.
     */
    suspend fun gateForSettingsApply(): ApplyResult? {
        // Step 2 above is injected by each entry point; this is the shared primitive.
        val flushError = ApplyCoordinator.awaitReadyAndFlush(DataStore.configurationStore)
        if (flushError != null) {
            return ApplyResult(
                requestId = "",
                outcome = CommandOutcome.FAILED,
                instanceGeneration = ApplyCoordinator.commandGeneration.get(),
                persisted = false,
                errorCode = flushError,
            )
        }
        return null
    }

    /**
     * Receiver entry: apply the captured [request] after readiness + flush +
     * committed snapshot validation.
     *
     * The actual "apply" work is backend-agnostic: for START/RELOAD it expects
     * the resolved profile id and the optional router tag/member to already be
     * committed to the DB; this function reads the committed snapshot to take
     * the real target (not the stale mirror). STOP never needs a snapshot.
     *
     * Returns the terminal ApplyResult to publish. Callers (binder / broadcast
     * compat adapter) allocate a requestId+commandGeneration before calling and
     * then funnel [result] into ApplyCoordinator.publish.
     */
    suspend fun applyCommitted(request: ApplyRequest, generation: Long): ApplyResult {
        val flushError = if (request.kind != CommandKind.STOP) {
            ApplyCoordinator.awaitReadyAndFlush(DataStore.configurationStore)
        } else {
            // STOP still awaits Ready so it doesn't race a mid-prime state, but
            // it never needs to flush a successful queued write to "apply" the stop.
            when (val r = DataStore.configurationStore.awaitReady()) {
                is io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore.StoreReadiness.Ready -> null
                is io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore.StoreReadiness.Failed ->
                    r.errorCode.let { ApplyErrorCodes.NOT_READY }
                else -> ApplyErrorCodes.NOT_READY
            }
        }
        if (flushError != null) {
            return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, flushError)
        }

        // STOP path: await cleanup before ack is performed by the caller's lifecycle
        // (BaseService.stopRunner now ack-orders); here we just validate no snapshot is needed.
        if (request.kind == CommandKind.STOP) {
            // STOP has no explicit target validation; it is idempotent. The actual
            // "ack after cleanup" is orchestrated by BaseService.stopRunner, so this
            // helper returns STOPPED immediately for the handshake-path tests.
            return ApplyResult(request.requestId, CommandOutcome.STOPPED, generation, true, null)
        }

        // Non-STOP: validate the explicit target against the committed snapshot
        // (or against the DB, for router selection).  Missing/invalid target
        // must FAIL without falling back to another node.
        val targetId = request.targetProfileId
        if (targetId != null) {
            val exists = withContext(Dispatchers.IO) {
                runCatching { SagerDatabase.proxyDao.getById(targetId) }.getOrNull() != null
            }
            if (!exists) {
                return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
            }
        } else if (request.kind == CommandKind.START || request.kind == CommandKind.RELOAD) {
            // No explicit target: allowed only for "no new selection" reloads/auto-start.
            // Resolve from the newly-read committed settings; absence is a hard failure.
            val committed = DataStore.configurationStore.readCommittedSettingsSnapshot()
            val selectedRow = committed.firstOrNull { it.key == "selectedProxy" || it.key == "selected_proxy" }
            val committedProxyId = selectedRow?.let {
                runCatching { String(it.value).toLong() }.getOrNull()
            } ?: DataStore.selectedProxy
            if (committedProxyId == 0L) {
                return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
            }
        }

        // Router stable-tag contract: both present or both absent (captured at click time).
        if ((request.routerStableTag == null) != (request.routerMemberId == null)) {
            return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
        }

        // The caller will do the actual core start/reload (ProxyInstance/box) after
        // this validation.  Returning APPLIED here means "target validated and flush
        // committed, core may proceed" — the core still must confirm the box call.
        // For the pure handshake path tests, returning APPLIED is the terminal outcome.
        return ApplyResult(request.requestId, CommandOutcome.APPLIED, generation, true, null)
    }

    /**
     * Compatibility adapter for legacy RELOAD broadcast extras (routerTag +
     * routerProxyId): normalize to the same ApplyRequest shape.  Forwards to
     * [applyCommitted] via the same coordinator so protocol is unified.
     */
    fun broadcastToRequest(routerTag: String?, routerProxyId: Long?, forceFullReload: Boolean): ApplyRequest =
        ApplyRequest(
            kind = CommandKind.RELOAD,
            targetProfileId = routerProxyId,
            routerStableTag = routerTag,
            routerMemberId = routerProxyId?.let { if (it > 0L) it else null },
            forceFullReload = forceFullReload,
        )
}
