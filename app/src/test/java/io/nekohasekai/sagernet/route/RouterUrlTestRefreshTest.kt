package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RouterGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterUrlTestRefreshTest {

    @Test
    fun refreshesOnlyAConnectedAutomaticRouter() {
        assertEquals(
            "router.sg",
            RouterUrlTestRefresh.tagFor(
                mode = RouterGroup.MODE_URL_TEST,
                enabled = true,
                stableTag = "router.sg",
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun skipsManualGroupsDisconnectedCoreAndBlankTags() {
        assertNull(
            RouterUrlTestRefresh.tagFor(
                mode = RouterGroup.MODE_SELECTOR,
                enabled = true,
                stableTag = "router.us",
                serviceConnected = true,
            ),
        )
        assertNull(
            RouterUrlTestRefresh.tagFor(
                mode = RouterGroup.MODE_URL_TEST,
                enabled = true,
                stableTag = "router.sg",
                serviceConnected = false,
            ),
        )
        assertNull(
            RouterUrlTestRefresh.tagFor(
                mode = RouterGroup.MODE_URL_TEST,
                enabled = false,
                stableTag = "router.sg",
                serviceConnected = true,
            ),
        )
        assertNull(
            RouterUrlTestRefresh.tagFor(
                routerGroup = null,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun runningCoreOnlyAcceptsBuiltUrlTestTags() {
        val tags = listOf("router.sg", "router.jp")
        assertTrue(RouterUrlTestRefresh.canRefreshRunningGroup("router.sg", tags))
        assertFalse(RouterUrlTestRefresh.canRefreshRunningGroup("router.us", tags))
        assertFalse(RouterUrlTestRefresh.canRefreshRunningGroup("", tags))
    }
}
