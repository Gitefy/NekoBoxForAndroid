package io.nekohasekai.sagernet.ui

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRouterSectionGateTest {
    @Test
    fun destroyedOrDetachedViewDropsLateResult() {
        assertFalse(GroupRouterSectionGate.canApplyUi(viewAlive = false, state = Lifecycle.State.STARTED))
        assertFalse(GroupRouterSectionGate.canApplyUi(viewAlive = true, state = Lifecycle.State.DESTROYED))
        assertTrue(GroupRouterSectionGate.canApplyUi(viewAlive = true, state = Lifecycle.State.CREATED))
        assertTrue(GroupRouterSectionGate.canApplyUi(viewAlive = true, state = Lifecycle.State.STARTED))
    }

    @Test
    fun staleRefreshGenerationCannotOverrideNewer() {
        val generation = 2
        assertFalse(GroupRouterSectionGate.isCurrentGeneration(started = 1, current = generation))
        assertTrue(GroupRouterSectionGate.isCurrentGeneration(started = 2, current = generation))
    }
}
