package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RestoreCoordinator
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ApplyService {

    fun recoveryFailure(
        request: ApplyRequest,
        generation: Long,
        recovery: RestoreCoordinator.Outcome,
    ): ApplyResult? {
        if (request.kind == CommandKind.STOP || recovery.success) return null
        return ApplyResult(
            request.requestId,
            CommandOutcome.FAILED,
            generation,
            false,
            recovery.error ?: ApplyErrorCodes.RESTORE_FAILED,
        )
    }

    suspend fun gateForSettingsApply(): ApplyResult? {
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

    fun resolveCommittedProfileId(request: ApplyRequest, committed: List<KeyValuePair>): Long? {
        request.targetProfileId?.let { explicit ->
            return explicit.takeIf { it > 0L }
        }
        val row = committed.firstOrNull { it.key == Key.PROFILE_ID } ?: return null
        val id = row.long ?: return null
        return id.takeIf { it > 0L }
    }

    /**
     * Readiness, flush, and target validation only. Never returns APPLIED/STOPPED;
     * the caller must run the real core action and then publish.
     */
    suspend fun validateCommitted(request: ApplyRequest, generation: Long): ApplyResult? {
        if (io.nekohasekai.sagernet.database.RestoreCoordinator.isActive() && request.kind != CommandKind.STOP) {
            return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.RESTORE_IN_PROGRESS)
        }
        val flushError = if (request.kind != CommandKind.STOP) {
            ApplyCoordinator.awaitReadyAndFlush(DataStore.configurationStore)
        } else {
            when (val r = DataStore.configurationStore.awaitReady()) {
                is io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore.StoreReadiness.Ready -> null
                is io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore.StoreReadiness.Failed ->
                    ApplyErrorCodes.NOT_READY
                else -> ApplyErrorCodes.NOT_READY
            }
        }
        if (flushError != null) {
            return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, flushError)
        }

        if (request.kind == CommandKind.STOP) return null

        val targetId = request.targetProfileId
        if (targetId != null) {
            if (targetId <= 0L) {
                return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
            }
            val exists = withContext(Dispatchers.IO) {
                runCatching { SagerDatabase.proxyDao.getById(targetId) }.getOrNull() != null
            }
            if (!exists) {
                return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
            }
        } else if (request.kind == CommandKind.START || request.kind == CommandKind.RELOAD) {
            val committed = DataStore.configurationStore.readCommittedSettingsSnapshotOffMain()
            val committedProxyId = resolveCommittedProfileId(request, committed)
            if (committedProxyId == null) {
                return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
            }
        }

        if ((request.routerStableTag == null) != (request.routerMemberId == null)) {
            return ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET)
        }
        return null
    }

    fun broadcastToRequest(routerTag: String?, routerProxyId: Long?, forceFullReload: Boolean): ApplyRequest =
        ApplyRequest(
            kind = CommandKind.RELOAD,
            targetProfileId = routerProxyId,
            routerStableTag = routerTag,
            routerMemberId = routerProxyId?.let { if (it > 0L) it else null },
            forceFullReload = forceFullReload,
        )
}
