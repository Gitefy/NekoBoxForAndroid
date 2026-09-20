package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLooperSelectionGatingTest {

    @Test
    fun queriesWhenForegroundRegardlessOfMainUrlTestTag() {
        assertTrue(
            TrafficLooper.shouldQueryUrlTestSelections(
                mainActivityForeground = true,
                hasMainUrlTestTag = false,
            )
        )
        assertTrue(
            TrafficLooper.shouldQueryUrlTestSelections(
                mainActivityForeground = true,
                hasMainUrlTestTag = true,
            )
        )
    }

    @Test
    fun queriesWhenBackgroundIfMainUrlTestTagPresent() {
        // Accounting depends on the main URL-test winner, so we must still poll.
        assertTrue(
            TrafficLooper.shouldQueryUrlTestSelections(
                mainActivityForeground = false,
                hasMainUrlTestTag = true,
            )
        )
    }

    @Test
    fun skipsWhenBackgroundAndNoMainUrlTestTag() {
        // No UI to display selections and no winner needed for accounting -> skip N JNI calls.
        assertFalse(
            TrafficLooper.shouldQueryUrlTestSelections(
                mainActivityForeground = false,
                hasMainUrlTestTag = false,
            )
        )
    }
}
