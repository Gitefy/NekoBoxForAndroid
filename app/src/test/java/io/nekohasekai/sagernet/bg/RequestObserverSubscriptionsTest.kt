package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestObserverSubscriptionsTest {
    @Test
    fun duplicateEnableIsIdempotent() {
        val subs = RequestObserverSubscriptions()
        assertTrue(subs.enable("client-a"))
        assertTrue(subs.enable("client-a"))
        assertTrue(subs.hasSubscribers())
        assertFalse(subs.disable("client-a"))
        assertFalse(subs.hasSubscribers())
        assertFalse(subs.disable("client-a"))
    }

    @Test
    fun callbackDeathStopsLastRequestObserver() {
        val subs = RequestObserverSubscriptions()
        subs.enable("traffic-client")
        subs.enable("request-client")
        assertTrue(subs.hasSubscribers())
        subs.remove("request-client")
        assertTrue(subs.hasSubscribers())
        subs.remove("traffic-client")
        assertFalse(subs.hasSubscribers())
        subs.remove("traffic-client")
        assertFalse(subs.hasSubscribers())
        assertFalse(subs.contains("traffic-client"))
    }
}
