package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CompletableDeferred

object RequestReloadAck {
    fun newFullReloadRequest(): ApplyRequest = ApplyRequest(
        kind = CommandKind.RELOAD,
        targetProfileId = null,
        routerStableTag = null,
        routerMemberId = null,
        forceFullReload = true,
    )

    suspend fun awaitApplied(
        request: ApplyRequest = newFullReloadRequest(),
        register: (String) -> CompletableDeferred<CommandOutcome> = ApplyCommandWaiter::register,
        send: (ApplyRequest) -> Unit,
        awaitOutcome: suspend (String, CompletableDeferred<CommandOutcome>, Long) -> CommandOutcome =
            { id, deferred, timeout -> ApplyCommandWaiter.await(id, deferred, timeout) },
        timeoutMs: Long = ApplyCoordinator.OBSERVE_TIMEOUT_MS,
    ): Boolean {
        val deferred = register(request.requestId)
        send(request)
        return awaitOutcome(request.requestId, deferred, timeoutMs) == CommandOutcome.APPLIED
    }
}
