package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RouterGroup

object UserStartTarget {
    data class RouterPage(
        val mode: Int,
        val selectedMemberId: Long,
    )

    data class Capture(
        val targetProfileId: Long?,
        val persistGlobalProfileId: Boolean,
        val errorCode: String?,
    )

    fun capture(
        globalSelectedId: Long,
        globalProfileValid: Boolean,
        inRouterGroupMode: Boolean,
        routerPage: RouterPage?,
        routerMemberValid: Boolean,
    ): Capture {
        if (globalSelectedId > 0L && globalProfileValid) {
            return Capture(globalSelectedId, persistGlobalProfileId = false, errorCode = null)
        }
        if (inRouterGroupMode && routerPage != null) {
            val memberId = routerPage.selectedMemberId
            if (memberId > 0L && routerMemberValid) {
                return Capture(memberId, persistGlobalProfileId = true, errorCode = null)
            }
            return Capture(null, persistGlobalProfileId = false, errorCode = ApplyErrorCodes.INVALID_TARGET)
        }
        return Capture(null, persistGlobalProfileId = false, errorCode = ApplyErrorCodes.INVALID_TARGET)
    }

    fun persistIfNeeded(capture: Capture, persistProfileId: (Long) -> Unit) {
        val id = capture.targetProfileId ?: return
        if (capture.persistGlobalProfileId) persistProfileId(id)
    }

    fun toStartRequest(targetProfileId: Long?): ApplyRequest =
        ApplyRequest(
            kind = CommandKind.START,
            targetProfileId = targetProfileId?.takeIf { it > 0L },
            routerStableTag = null,
            routerMemberId = null,
        )

    fun isUrlTestWithoutMember(routerPage: RouterPage?): Boolean =
        routerPage != null &&
            routerPage.mode == RouterGroup.MODE_URL_TEST &&
            routerPage.selectedMemberId <= 0L
}

object ApplyErrorMessages {
    fun stringRes(errorCode: String?): Int? = when (errorCode) {
        ApplyErrorCodes.INVALID_TARGET -> R.string.profile_empty
        ApplyErrorCodes.NOT_READY -> R.string.settings_not_ready
        ApplyErrorCodes.FLUSH_FAILED, ApplyErrorCodes.PERSIST_FAILED -> R.string.settings_flush_failed
        ApplyErrorCodes.RESTORE_IN_PROGRESS -> R.string.restore_in_progress
        else -> null
    }
}
