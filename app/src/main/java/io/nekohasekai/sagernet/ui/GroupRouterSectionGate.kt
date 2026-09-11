package io.nekohasekai.sagernet.ui

import androidx.lifecycle.Lifecycle

object GroupRouterSectionGate {
    fun canApplyUi(viewAlive: Boolean, state: Lifecycle.State): Boolean {
        return viewAlive && state.isAtLeast(Lifecycle.State.CREATED)
    }

    fun isCurrentGeneration(started: Int, current: Int): Boolean = started == current
}
