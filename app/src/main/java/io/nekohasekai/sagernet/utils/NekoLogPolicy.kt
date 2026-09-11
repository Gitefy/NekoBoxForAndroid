package io.nekohasekai.sagernet.utils

object NekoLogPolicy {
    fun desiredEnabled(storeReady: Boolean, committedLogLevel: Int): Boolean {
        if (!storeReady) return false
        return committedLogLevel > 0
    }

    enum class PageState { DISABLED, EMPTY, CONTENT }

    fun pageState(storeReady: Boolean, committedLogLevel: Int, logText: String): PageState {
        if (storeReady && committedLogLevel <= 0) return PageState.DISABLED
        if (logText.isBlank()) return PageState.EMPTY
        return PageState.CONTENT
    }
}
