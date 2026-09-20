package io.nekohasekai.sagernet.ktx

/**
 * Skip a second layout/smooth-scroll when the target row is already fully visible.
 */
object ProfileListScroll {
    fun shouldSkip(firstCompletelyVisible: Int, target: Int): Boolean {
        if (target < 0) return true
        if (firstCompletelyVisible < 0) return false
        return firstCompletelyVisible == target
    }
}
