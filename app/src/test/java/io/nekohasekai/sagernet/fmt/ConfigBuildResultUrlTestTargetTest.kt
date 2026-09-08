package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigBuildResultUrlTestTargetTest {
    @Test
    fun connectionTestTargetIsIndependentFromTrafficStatisticsTarget() {
        val result = ConfigBuildResult(
            config = "{}",
            externalIndex = emptyList(),
            mainEntId = 1L,
            trafficMap = emptyMap(),
            profileTagMap = mapOf(1L to "proxy-1"),
            selectorGroupId = -1L,
            mainUrlTestTag = null,
            routerAllMemberIds = emptySet(),
            connectionTestTargetTag = "proxy-1",
        )

        assertNull(result.mainUrlTestTag)
        assertEquals("proxy-1", result.connectionTestTargetTag)
    }
}
