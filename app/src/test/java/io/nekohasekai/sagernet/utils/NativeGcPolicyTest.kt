package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGcPolicyTest {
    @Test
    fun uiHiddenDoesNotForceNativeGc() {
        assertFalse(
            NativeGcPolicy.shouldForceNativeGc(
                level = NativeGcPolicy.TRIM_MEMORY_UI_HIDDEN,
                nowElapsed = 120_000L,
                lastElapsed = 0L,
            )
        )
    }

    @Test
    fun otherLevelsHonorCooldown() {
        assertFalse(
            NativeGcPolicy.shouldForceNativeGc(
                level = 40,
                nowElapsed = 30_000L,
                lastElapsed = 0L,
            )
        )
        assertTrue(
            NativeGcPolicy.shouldForceNativeGc(
                level = 40,
                nowElapsed = 60_000L,
                lastElapsed = 0L,
            )
        )
        assertTrue(
            NativeGcPolicy.shouldForceNativeGc(
                level = 80,
                nowElapsed = 90_000L,
                lastElapsed = 0L,
            )
        )
    }
}
