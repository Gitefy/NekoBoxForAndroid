package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PingWorkerCountTest {
    @Test
    fun capsWorkersAtProfilesAndDeviceLimit() {
        assertEquals(0, pingWorkerCount(0, 500))
        assertEquals(3, pingWorkerCount(3, 500))
        assertEquals(16, pingWorkerCount(500, 500))
        assertEquals(5, pingWorkerCount(500, 5))
        assertEquals(1, pingWorkerCount(500, 0))
    }
}
