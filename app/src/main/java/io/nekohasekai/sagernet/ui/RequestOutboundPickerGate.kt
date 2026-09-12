package io.nekohasekai.sagernet.ui

import androidx.lifecycle.Lifecycle

object RequestOutboundPickerGate {
    fun canShow(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.STARTED)
}
