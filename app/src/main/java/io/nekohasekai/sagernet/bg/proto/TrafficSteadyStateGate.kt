package io.nekohasekai.sagernet.bg.proto

/**
 * Evaluates whether post-updateAll processing (rate aggregation, snapshot allocation,
 * IPC dispatch, and notification updates) can be safely short-circuited during the
 * traffic polling loop.
 *
 * Short-circuiting is ONLY valid when all of the following hold:
 * 1. [anyTrafficDelta] is false: no bytes were transferred on any tracked tag in this tick.
 * 2. [lastRatesWereZero] is true: the previous tick's rates were ALREADY zero. This ensures
 *    that any transition from non-zero traffic to zero traffic executes a full pass to
 *    publish the 0 B/s drop to UI and notification.
 * 3. [selectionChanged] is false: Router URL test selections have not changed.
 */
object TrafficSteadyStateGate {
    fun shouldShortCircuit(
        anyTrafficDelta: Boolean,
        lastRatesWereZero: Boolean,
        selectionChanged: Boolean,
    ): Boolean = !anyTrafficDelta && lastRatesWereZero && !selectionChanged
}
