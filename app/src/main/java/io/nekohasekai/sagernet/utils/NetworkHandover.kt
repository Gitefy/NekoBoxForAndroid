package io.nekohasekai.sagernet.utils

/** Actor-confined: initial availability is not a handover; recovery after loss is. */
internal class NetworkHandover<T> {
    private var previous: T? = null
    private var hadNetwork = false

    fun update(network: T?): Boolean {
        val changed = network != null && hadNetwork && previous != network
        previous = network
        if (network != null) hadNetwork = true
        return changed
    }
}
