package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStartTargetTest {

    @Test
    fun normalSelectedProfileIsCapturedIntoStartRequest() {
        val capture = UserStartTarget.capture(
            globalSelectedId = 42L,
            globalProfileValid = true,
            inRouterGroupMode = false,
            routerPage = null,
            routerMemberValid = false,
        )
        val request = UserStartTarget.toStartRequest(capture.targetProfileId)
        assertEquals(42L, request.targetProfileId)
        assertEquals(CommandKind.START, request.kind)
        assertNull(request.routerStableTag)
        assertNull(request.routerMemberId)
        assertFalse(capture.persistGlobalProfileId)
        assertNull(capture.errorCode)
    }

    @Test
    fun vpnPermissionRoundTripPreservesCapturedTarget() {
        val contract = VpnRequestActivity.StartService()
        contract.captureInput(99L)
        assertEquals(99L, contract.pendingTarget)
        val afterPermission = UserStartTarget.toStartRequest(contract.pendingTarget)
        assertEquals(99L, afterPermission.targetProfileId)
        assertEquals(CommandKind.START, afterPermission.kind)
    }

    @Test
    fun routerSelectedMemberSeedsStartOnlyWhenGlobalTargetMissing() {
        val persisted = LinkedHashMap<String, Long>()
        val capture = UserStartTarget.capture(
            globalSelectedId = 0L,
            globalProfileValid = false,
            inRouterGroupMode = true,
            routerPage = UserStartTarget.RouterPage(RouterGroup.MODE_SELECTOR, 15L),
            routerMemberValid = true,
        )
        UserStartTarget.persistIfNeeded(capture) { persisted[Key.PROFILE_ID] = it }
        val request = UserStartTarget.toStartRequest(capture.targetProfileId)
        assertEquals(15L, request.targetProfileId)
        assertTrue(capture.persistGlobalProfileId)
        assertEquals(15L, persisted[Key.PROFILE_ID])
    }

    @Test
    fun existingGlobalTargetWinsOverRouterSelection() {
        val persisted = LinkedHashMap<String, Long>()
        val capture = UserStartTarget.capture(
            globalSelectedId = 8L,
            globalProfileValid = true,
            inRouterGroupMode = true,
            routerPage = UserStartTarget.RouterPage(RouterGroup.MODE_SELECTOR, 15L),
            routerMemberValid = true,
        )
        UserStartTarget.persistIfNeeded(capture) { persisted[Key.PROFILE_ID] = it }
        assertEquals(8L, UserStartTarget.toStartRequest(capture.targetProfileId).targetProfileId)
        assertFalse(capture.persistGlobalProfileId)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun invalidTargetShowsProfileEmpty() {
        val urlTestNoMember = UserStartTarget.capture(
            globalSelectedId = 0L,
            globalProfileValid = false,
            inRouterGroupMode = true,
            routerPage = UserStartTarget.RouterPage(RouterGroup.MODE_URL_TEST, 0L),
            routerMemberValid = false,
        )
        assertNull(urlTestNoMember.targetProfileId)
        assertEquals(ApplyErrorCodes.INVALID_TARGET, urlTestNoMember.errorCode)
        assertTrue(UserStartTarget.isUrlTestWithoutMember(urlTestNoMember.let {
            UserStartTarget.RouterPage(RouterGroup.MODE_URL_TEST, 0L)
        }))
        assertEquals(R.string.profile_empty, ApplyErrorMessages.stringRes(ApplyErrorCodes.INVALID_TARGET))
        val failed = ApplyResult("r", CommandOutcome.FAILED, 1L, false, ApplyErrorCodes.INVALID_TARGET)
        assertEquals(ApplyErrorCodes.INVALID_TARGET, failed.errorCode)
        assertEquals(R.string.profile_empty, ApplyErrorMessages.stringRes(failed.errorCode))
    }

    @Test
    fun flushFailureIsNotReportedAsProfileEmpty() {
        val failed = ApplyResult("r", CommandOutcome.FAILED, 1L, false, ApplyErrorCodes.FLUSH_FAILED)
        assertEquals(ApplyErrorCodes.FLUSH_FAILED, failed.errorCode)
        assertEquals(R.string.settings_flush_failed, ApplyErrorMessages.stringRes(failed.errorCode))
        assertNotEquals(R.string.profile_empty, ApplyErrorMessages.stringRes(failed.errorCode))
        assertNotEquals(R.string.profile_empty, ApplyErrorMessages.stringRes(ApplyErrorCodes.NOT_READY))
        assertEquals(R.string.settings_not_ready, ApplyErrorMessages.stringRes(ApplyErrorCodes.NOT_READY))
        assertEquals(R.string.restore_in_progress, ApplyErrorMessages.stringRes(ApplyErrorCodes.RESTORE_IN_PROGRESS))
    }
}
