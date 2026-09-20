package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPreferenceCatalogTest {
    @Test
    fun loadingKeepsCurrentValueAndIsNotInteractive() {
        val model = GroupPreferenceCatalog.loading("12")
        assertEquals(GroupPreferenceCatalog.State.Loading, model.state)
        assertFalse(model.interactive)
        assertEquals(listOf("12"), model.entryValues.map { it.toString() })
        assertEquals("12", GroupPreferenceCatalog.summary("12", model, "fallback"))
    }

    @Test
    fun loadingBlankValueDoesNotInventAGroupId() {
        val model = GroupPreferenceCatalog.loading("0")
        assertTrue(model.items.isEmpty())
        assertFalse(model.interactive)
        assertEquals("fallback", GroupPreferenceCatalog.summary("0", model, "fallback"))
        assertEquals("fallback", GroupPreferenceCatalog.summary(null, model, "fallback"))
    }

    @Test
    fun readyReplacesPlaceholderWithRealNames() {
        val model = GroupPreferenceCatalog.ready(
            listOf(GroupPreferenceItem(12, "US"), GroupPreferenceItem(13, "SG")),
        )
        assertEquals(GroupPreferenceCatalog.State.Ready, model.state)
        assertTrue(model.interactive)
        assertEquals("US", GroupPreferenceCatalog.summary("12", model, "fallback"))
        assertEquals("SG", GroupPreferenceCatalog.summary("13", model, "fallback"))
        assertEquals("fallback", GroupPreferenceCatalog.summary("99", model, "fallback"))
    }

    @Test
    fun emptyCatalogStaysSelectableWithoutChangingValue() {
        val model = GroupPreferenceCatalog.ready(emptyList())
        assertEquals(GroupPreferenceCatalog.State.Empty, model.state)
        assertTrue(model.interactive)
        assertTrue(model.entries.isEmpty())
        assertEquals("fallback", GroupPreferenceCatalog.summary("12", model, "fallback"))
    }
}

class OutboundPreferenceSummaryTest {
    @Test
    fun loadingDoesNotShowInvalidRouter() {
        assertEquals(
            "menu",
            OutboundPreferenceSummary.resolve(
                value = OutboundPreference.VALUE_SELECT_ROUTER,
                profileName = null,
                routerName = null,
                routerMissing = false,
                invalidRouterLabel = "invalid",
                fallback = "menu",
            ),
        )
    }

    @Test
    fun successShowsLoadedNames() {
        assertEquals(
            "US-01",
            OutboundPreferenceSummary.resolve(
                value = OutboundPreference.VALUE_SELECT_PROFILE,
                profileName = "US-01",
                routerName = null,
                routerMissing = false,
                invalidRouterLabel = "invalid",
                fallback = "menu",
            ),
        )
        assertEquals(
            "US",
            OutboundPreferenceSummary.resolve(
                value = OutboundPreference.VALUE_SELECT_ROUTER,
                profileName = null,
                routerName = "US",
                routerMissing = false,
                invalidRouterLabel = "invalid",
                fallback = "menu",
            ),
        )
    }

    @Test
    fun emptyMissingRouterShowsInvalidAfterLoad() {
        assertEquals(
            "invalid",
            OutboundPreferenceSummary.resolve(
                value = OutboundPreference.VALUE_SELECT_ROUTER,
                profileName = null,
                routerName = null,
                routerMissing = true,
                invalidRouterLabel = "invalid",
                fallback = "menu",
            ),
        )
        assertEquals(
            "menu",
            OutboundPreferenceSummary.resolve(
                value = OutboundPreference.VALUE_SELECT_PROFILE,
                profileName = null,
                routerName = null,
                routerMissing = false,
                invalidRouterLabel = "invalid",
                fallback = "menu",
            ),
        )
    }

    @Test
    fun cacheKeyIncludesSelectedIds() {
        assertEquals("p:9", OutboundPreferenceSummary.cacheKey(OutboundPreference.VALUE_SELECT_PROFILE, 9, 0))
        assertEquals("r:4", OutboundPreferenceSummary.cacheKey(OutboundPreference.VALUE_SELECT_ROUTER, 0, 4))
        assertEquals("0", OutboundPreferenceSummary.cacheKey("0", 0, 0))
    }
}

class PreferenceAsyncGuardTest {
    @Test
    fun attachLoadSuccessApplies() {
        assertTrue(PreferenceAsyncGuard.shouldApply(attached = true, startedGeneration = 1, currentGeneration = 1))
    }

    @Test
    fun detachBeforeLoadCompletesDropsResult() {
        assertFalse(PreferenceAsyncGuard.shouldApply(attached = false, startedGeneration = 1, currentGeneration = 1))
        assertFalse(PreferenceAsyncGuard.shouldApply(attached = false, startedGeneration = 1, currentGeneration = 2))
    }

    @Test
    fun attachAThenDetachThenAttachBKeepsBRegardlessOfArrivalOrder() {
        var generation = 0
        val attachA = ++generation
        generation++ // detach A
        val attachB = ++generation
        assertFalse(PreferenceAsyncGuard.shouldApply(true, attachA, generation))
        assertTrue(PreferenceAsyncGuard.shouldApply(true, attachB, generation))
        assertFalse(PreferenceAsyncGuard.shouldApply(true, attachA, generation))
    }

    @Test
    fun valueChangeDuringLoadDoesNotWriteOldSummary() {
        assertFalse(
            PreferenceAsyncGuard.shouldApplySummary(
                attached = true,
                startedGeneration = 3,
                currentGeneration = 3,
                requestedCacheKey = "p:1",
                liveCacheKey = "p:2",
            ),
        )
        assertTrue(
            PreferenceAsyncGuard.shouldApplySummary(
                attached = true,
                startedGeneration = 3,
                currentGeneration = 3,
                requestedCacheKey = "p:2",
                liveCacheKey = "p:2",
            ),
        )
    }

    @Test
    fun fragmentRecreationIsANewGeneration() {
        var generation = 1
        generation++
        val recreated = ++generation
        assertTrue(PreferenceAsyncGuard.shouldApply(true, recreated, generation))
        assertFalse(PreferenceAsyncGuard.shouldApply(true, 1, generation))
    }

    @Test
    fun emptyThenLaterReadyUsesLatestGeneration() {
        val emptyLoad = 1
        val readyLoad = 2
        assertFalse(PreferenceAsyncGuard.shouldApply(true, emptyLoad, readyLoad))
        assertTrue(PreferenceAsyncGuard.shouldApply(true, readyLoad, readyLoad))
    }

    @Test
    fun rapidAttachDetachNeverAppliesStaleToken() {
        var generation = 0
        val tokens = mutableListOf<Int>()
        repeat(8) {
            tokens += ++generation
            generation++
        }
        val current = ++generation
        tokens.forEach { token ->
            assertFalse(PreferenceAsyncGuard.shouldApply(true, token, current))
        }
        assertTrue(PreferenceAsyncGuard.shouldApply(true, current, current))
    }

    @Test
    fun persistedValueMissingStillUsesFallbackNotInventedId() {
        val loading = GroupPreferenceCatalog.loading("99")
        assertEquals(listOf("99"), loading.entryValues.map { it.toString() })
        val ready = GroupPreferenceCatalog.ready(listOf(GroupPreferenceItem(1, "US")))
        assertEquals("fallback", GroupPreferenceCatalog.summary("99", ready, "fallback"))
        assertFalse(ready.entryValues.map { it.toString() }.contains("99"))
    }
}

