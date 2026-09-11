package io.nekohasekai.sagernet.ui

class RequestObserverUiGate {
    var pageVisible: Boolean = false
        private set

    fun onPageVisibility(visible: Boolean, binderConnected: Boolean): Boolean {
        pageVisible = visible
        return binderConnected
    }

    fun shouldEnableAfterConnect(): Boolean = pageVisible
}
