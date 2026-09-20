package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSteadyStateGateTest {

    @Test
    fun steadyStateZeroToZeroShortCircuits() {
        // Delta was 0, last rates were already 0, selection unchanged -> short-circuit.
        assertTrue(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = true,
                selectionChanged = false,
            )
        )
    }

    @Test
    fun zeroToNonZeroNeverShortCircuits() {
        // Traffic just started moving -> must run full pass to update rates and counters.
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = true,
                lastRatesWereZero = true,
                selectionChanged = false,
            )
        )
    }

    @Test
    fun nonZeroToZeroNeverShortCircuits() {
        // Traffic just stopped. Last rates were >0, so this first zero tick MUST run
        // the full pass to compute 0 B/s rates and update the notification/UI speed to zero.
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = false,
                selectionChanged = false,
            )
        )
    }

    @Test
    fun activeTrafficContinuingNeverShortCircuits() {
        // Traffic is actively flowing across ticks.
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = true,
                lastRatesWereZero = false,
                selectionChanged = false,
            )
        )
    }

    @Test
    fun selectionChangeWhileIdleNeverShortCircuits() {
        // No traffic moved, but a Router URL test winner changed -> must run full pass
        // to broadcast the new selection to the UI.
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = true,
                selectionChanged = true,
            )
        )
    }

    @Test
    fun selectionChangeWithActiveTrafficNeverShortCircuits() {
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = true,
                lastRatesWereZero = false,
                selectionChanged = true,
            )
        )
    }

    @Test
    fun multipleIdleTicksSequence() {
        // Simulates a sequence of ticks:
        // Tick 1: Active traffic (100 KB) -> not short-circuited, lastRatesWereZero becomes false
        var lastRatesWereZero = false
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = true,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = false,
            )
        )
        // Tick 2: Traffic stops (0 bytes) -> first zero tick, NOT short-circuited (publishes 0 B/s drop)
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = false,
            )
        )
        // After tick 2's full pass, rates are 0 -> lastRatesWereZero becomes true
        lastRatesWereZero = true

        // Tick 3: Still 0 bytes -> SHORT-CIRCUITED!
        assertTrue(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = false,
            )
        )

        // Tick 4: Still 0 bytes -> SHORT-CIRCUITED!
        assertTrue(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = false,
            )
        )

        // Tick 5: Still 0 bytes, but URL test selection changes -> NOT short-circuited!
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = true,
            )
        )

        // Tick 6: Still 0 bytes, selection unchanged again -> SHORT-CIRCUITED!
        assertTrue(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = false,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = false,
            )
        )

        // Tick 7: Traffic resumes! -> NOT short-circuited!
        assertFalse(
            TrafficSteadyStateGate.shouldShortCircuit(
                anyTrafficDelta = true,
                lastRatesWereZero = lastRatesWereZero,
                selectionChanged = false,
            )
        )
    }
}
