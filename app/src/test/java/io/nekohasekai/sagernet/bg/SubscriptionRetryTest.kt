package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class SubscriptionRetryTest {
    @Test fun failuresRetryBeforeTheNextDailyPeriodButPermanentErrorsAreBounded() {
        assertTrue(shouldRetrySubscriptionUpdate(true, 0))
        assertTrue(shouldRetrySubscriptionUpdate(true, 2))
        assertFalse(shouldRetrySubscriptionUpdate(true, 3))
        assertFalse(shouldRetrySubscriptionUpdate(false, 0))
    }
}
