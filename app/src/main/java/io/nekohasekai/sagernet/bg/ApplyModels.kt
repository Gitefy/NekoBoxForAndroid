package io.nekohasekai.sagernet.bg

import java.util.UUID

enum class CommandKind { START, RELOAD, STOP }
enum class CommandOutcome { APPLIED, STOPPED, FAILED, SUPERSEDED }

data class ApplyRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val kind: CommandKind,
    val targetProfileId: Long?,
    val routerStableTag: String?,
    val routerMemberId: Long?,
    val forceFullReload: Boolean = false,
) {
    init {
        require((routerStableTag == null) == (routerMemberId == null)) {
            "routerStableTag and routerMemberId must both be null or both non-null"
        }
    }

    companion object {
        fun generateRequestId(): String = UUID.randomUUID().toString()
    }
}

data class ApplyResult(
    val requestId: String,
    val outcome: CommandOutcome,
    val instanceGeneration: Long,
    val persisted: Boolean,
    val errorCode: String?,
)

object ApplyErrorCodes {
    const val FLUSH_FAILED = "FLUSH_FAILED"
    const val PERSIST_FAILED = "PERSIST_FAILED"
    const val INVALID_TARGET = "INVALID_TARGET"
    const val NOT_READY = "NOT_READY"
    const val TIMEOUT = "TIMEOUT"
    const val CORE_FAILED = "CORE_FAILED"
}
