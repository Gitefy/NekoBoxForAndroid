package moe.matsuri.nb4a

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NativeNetworkBindingTest {
    @Test fun eachNewSocketUsesTheSelectedNetworkAndProtectsBeforeBinding() {
        var selected = "wifi"
        val actions = mutableListOf<String>()
        fun dial() = protectAndBindNetwork(
            current = { selected },
            protect = { actions += "protect"; true },
            bind = { actions += "bind:$it" },
        )
        dial()
        selected = "5g"
        dial()
        selected = "wifi"
        dial()
        assertEquals(listOf("protect", "bind:wifi", "protect", "bind:5g", "protect", "bind:wifi"), actions)
    }

    @Test fun anUnavailableNetworkDoesNotCreateAnUnboundProtectedSocket() {
        var protected = false
        assertThrows(IOException::class.java) {
            protectAndBindNetwork<String>(
                current = { null },
                protect = { protected = true; true },
                bind = { fail("must not bind") },
            )
        }
        assertFalse(protected)
    }

    @Test fun protectionFailureStopsBeforeBinding() {
        assertThrows(IOException::class.java) {
            protectAndBindNetwork(current = { "5g" }, protect = { false }, bind = { fail("must not bind") })
        }
    }

    @Test fun bindingFailureIsReturnedToDialer() {
        val error = IOException("network was lost")
        val caught = assertThrows(IOException::class.java) {
            protectAndBindNetwork(current = { "5g" }, protect = { true }, bind = { throw error })
        }
        assertSame(error, caught)
    }

    @Test fun handoverDuringBindingRejectsTheOldNetworkSocket() {
        var selected = 1L
        assertThrows(IOException::class.java) {
            protectAndBindNetwork(current = { selected }, protect = { true }, bind = { selected = 2L })
        }
    }
}
