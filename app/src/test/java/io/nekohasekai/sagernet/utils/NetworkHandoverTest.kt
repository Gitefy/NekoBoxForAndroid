package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

class NetworkHandoverTest {
    @Test fun wifiCellularAndReturnEachRecoverOnce() {
        val state = NetworkHandover<String>()
        assertFalse(state.update("wifi"))
        assertFalse(state.update("wifi"))
        assertTrue(state.update("cellular"))
        assertFalse(state.update("cellular"))
        assertTrue(state.update("wifi"))
    }

    @Test fun lossAndReturnToSameNetworkStillRecover() {
        val state = NetworkHandover<String>()
        assertFalse(state.update(null))
        assertFalse(state.update("wifi"))
        assertFalse(state.update(null))
        assertFalse(state.update(null))
        assertTrue(state.update("wifi"))
        assertFalse(state.update("wifi"))
    }

    @Test fun networkIdentityAndInterfaceBothMatter() {
        val state = NetworkHandover<Pair<Long, String>>()
        assertFalse(state.update(1L to "wlan0"))
        assertTrue(state.update(2L to "wlan0"))
        assertTrue(state.update(2L to "rmnet0"))
        assertFalse(state.update(2L to "rmnet0"))
    }

    @Test fun newServiceDoesNotInheritOldRecoveryState() {
        val old = NetworkHandover<String>()
        old.update("wifi")
        old.update(null)
        assertFalse(NetworkHandover<String>().update("cellular"))
    }
}
