package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RouterGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterStartSeedTest {

    @Test
    fun prefersRuntimeWinnerWhenItIsStillAMember() {
        assertEquals(
            20L,
            RouterStartSeed.resolve(
                mode = RouterGroup.MODE_URL_TEST,
                uiSelectedId = 20L,
                persistedSelectedId = 10L,
                memberIdsInOrder = listOf(10L, 20L, 30L),
            ),
        )
    }

    @Test
    fun fallsBackToPersistedSelectionWhenRuntimeWinnerIsMissing() {
        assertEquals(
            10L,
            RouterStartSeed.resolve(
                mode = RouterGroup.MODE_URL_TEST,
                uiSelectedId = 99L,
                persistedSelectedId = 10L,
                memberIdsInOrder = listOf(10L, 20L),
            ),
        )
    }

    @Test
    fun urlTestWithoutSelectionUsesFirstMemberInUserOrder() {
        assertEquals(
            30L,
            RouterStartSeed.resolve(
                inputs = RouterStartInputs(
                    mode = RouterGroup.MODE_URL_TEST,
                    uiSelectedId = null,
                    persistedSelectedId = RouterGroup.NO_SELECTION,
                    routerId = 7L,
                ),
                memberIdsInOrder = listOf(30L, 10L),
            ),
        )
    }

    @Test
    fun urlTestStaleSelectionFallsBackToFirstMember() {
        assertEquals(
            30L,
            RouterStartSeed.resolve(
                mode = RouterGroup.MODE_URL_TEST,
                uiSelectedId = 99L,
                persistedSelectedId = 98L,
                memberIdsInOrder = listOf(30L, 10L),
            ),
        )
    }

    @Test
    fun selectorWithoutSelectionDoesNotPickTheFirstMember() {
        assertEquals(
            0L,
            RouterStartSeed.resolve(
                mode = RouterGroup.MODE_SELECTOR,
                uiSelectedId = null,
                persistedSelectedId = RouterGroup.NO_SELECTION,
                memberIdsInOrder = listOf(30L, 10L),
            ),
        )
    }

    @Test
    fun selectorKeepsAPersistedMember() {
        assertEquals(
            10L,
            RouterStartSeed.resolve(
                mode = RouterGroup.MODE_SELECTOR,
                uiSelectedId = null,
                persistedSelectedId = 10L,
                memberIdsInOrder = listOf(30L, 10L),
            ),
        )
    }

    @Test
    fun emptyMembershipCannotStart() {
        assertEquals(
            0L,
            RouterStartSeed.resolve(
                mode = RouterGroup.MODE_URL_TEST,
                uiSelectedId = 10L,
                persistedSelectedId = 10L,
                memberIdsInOrder = emptyList(),
            ),
        )
    }
}
