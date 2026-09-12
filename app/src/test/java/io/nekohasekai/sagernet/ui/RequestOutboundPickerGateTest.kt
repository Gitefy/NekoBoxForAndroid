package io.nekohasekai.sagernet.ui

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestOutboundPickerGateTest {
    @Test
    fun pickerResultIsDroppedWhenViewIsNotStarted() {
        assertFalse(RequestOutboundPickerGate.canShow(Lifecycle.State.DESTROYED))
        assertFalse(RequestOutboundPickerGate.canShow(Lifecycle.State.CREATED))
        assertTrue(RequestOutboundPickerGate.canShow(Lifecycle.State.STARTED))
        assertTrue(RequestOutboundPickerGate.canShow(Lifecycle.State.RESUMED))
    }
}
