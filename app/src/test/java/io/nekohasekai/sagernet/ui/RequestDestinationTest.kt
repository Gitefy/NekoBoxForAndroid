package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RequestRuleFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDestinationTest {
    @Test
    fun requestDisplayDoesNotDuplicatePort() {
        assertEquals("1.2.3.4:443", RequestDestination.formatHostPort("1.2.3.4", 443))
        assertEquals("[2001:db8::1]:443", RequestDestination.formatHostPort("2001:db8::1", 443))
        assertFalse(RequestDestination.formatHostPort("1.2.3.4", 443).contains("443:443"))
        assertFalse(RequestDestination.isRawIp("1.2.3.4:443"))
        assertTrue(RequestDestination.isRawIp("1.2.3.4"))
        assertTrue(RequestDestination.isRawIp("2001:db8::1"))
    }

    @Test
    fun destinationIPv6RuleUsesRawAddressWithoutPort() {
        val rule = RequestRuleFactory.build(
            match = RequestRuleFactory.MatchKind.DEST_IP,
            outboundKind = RequestRuleFactory.OutboundKind.REJECT,
            domain = "",
            packageName = "",
            destinationIp = "2001:db8::1",
            routerStableTag = "",
            routersByStableTag = emptyMap(),
        )!!.toRuleEntity()
        assertEquals("2001:db8::1", rule.ip)
        assertFalse(rule.ip.contains("["))
        assertFalse(rule.ip.contains("]:"))
        assertFalse(rule.ip.endsWith(":443"))
    }
}

class RequestObserverUiGateTest {
    @Test
    fun requestPageVisibleBeforeBinderConnectEnablesAfterConnect() {
        val gate = RequestObserverUiGate()
        assertFalse(gate.onPageVisibility(true, binderConnected = false))
        assertTrue(gate.pageVisible)
        assertTrue(gate.shouldEnableAfterConnect())
    }
}
