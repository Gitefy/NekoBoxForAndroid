package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTagIndexTest {
    @Test
    fun firstIdWinsWhenTagsCollideMatchingFilterValues() {
        val map = linkedMapOf(
            1L to "proxy-a",
            2L to "proxy-b",
            3L to "proxy-a",
        )
        val index = ProfileTagIndex.byTag(map)
        assertEquals(1L, ProfileTagIndex.idFor(index, "proxy-a"))
        assertEquals(2L, ProfileTagIndex.idFor(index, "proxy-b"))
        assertNull(ProfileTagIndex.idFor(index, "missing"))
        assertEquals(
            map.filterValues { it == "proxy-a" }.keys.firstOrNull(),
            ProfileTagIndex.idFor(index, "proxy-a"),
        )
    }

    @Test
    fun emptyMapHasNoFallbackId() {
        assertNull(ProfileTagIndex.idFor(ProfileTagIndex.byTag(emptyMap()), "proxy"))
        assertEquals(-1L, ProfileTagIndex.idFor(ProfileTagIndex.byTag(emptyMap()), "proxy") ?: -1L)
    }

    @Test
    fun configBuildResultExposesTheSameIndex() {
        val result = ConfigBuildResult(
            config = "{}",
            externalIndex = emptyList(),
            mainEntId = 1L,
            trafficMap = emptyMap(),
            profileTagMap = linkedMapOf(7L to "node-7", 8L to "node-8"),
            selectorGroupId = -1L,
        )
        assertEquals(7L, result.profileIdByTag["node-7"])
        assertEquals(8L, result.profileIdByTag["node-8"])
        assertNull(result.profileIdByTag["missing"])
    }
}
