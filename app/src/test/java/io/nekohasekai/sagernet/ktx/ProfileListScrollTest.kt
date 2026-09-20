package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileListScrollTest {
    @Test
    fun skipsWhenTargetIsAlreadyCompletelyVisible() {
        assertTrue(ProfileListScroll.shouldSkip(firstCompletelyVisible = 4, target = 4))
    }

    @Test
    fun scrollsWhenLayoutHasNotSettledOrTargetDiffers() {
        assertFalse(ProfileListScroll.shouldSkip(firstCompletelyVisible = -1, target = 4))
        assertFalse(ProfileListScroll.shouldSkip(firstCompletelyVisible = 0, target = 4))
        assertTrue(ProfileListScroll.shouldSkip(firstCompletelyVisible = 0, target = -3))
    }
}
