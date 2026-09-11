package io.nekohasekai.sagernet.utils

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ThemeColdStartTest {

    @Before
    fun resetNightCache() {
        Theme.currentNightMode = Theme.UNPINNED_NIGHT_MODE
    }

    @Test
    fun loadingMustNotPinDefaultNightModeBeforeStoreReady() {
        val nightYes = 1
        val mode = Theme.getNightMode(storeReady = false, committedNightTheme = nightYes)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, mode)
        assertEquals(Theme.UNPINNED_NIGHT_MODE, Theme.currentNightMode)

        val afterReady = Theme.getNightMode(storeReady = true, committedNightTheme = nightYes)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, afterReady)
        assertEquals(nightYes, Theme.currentNightMode)

        val appearance = AppearanceBootstrap.fromCommitted(
            appTheme = Theme.BLACK,
            nightTheme = nightYes,
            appLanguage = "zh-CN",
        )
        assertEquals(Theme.BLACK, appearance.appTheme)
        assertEquals(nightYes, appearance.nightTheme)
        assertEquals("zh-CN", appearance.appLanguage)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, Theme.getNightMode(appearance.nightTheme))
    }
}
