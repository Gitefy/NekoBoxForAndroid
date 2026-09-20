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
