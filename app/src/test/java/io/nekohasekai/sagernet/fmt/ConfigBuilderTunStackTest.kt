package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.TunImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigBuilderTunStackTest {
    @Test
    fun omitsStackForGoDefault() {
        assertNull(tunStackOption(TunImplementation.GO))
    }

    @Test
    fun writesDeprecatedStacksForFallbacks() {
        assertEquals("gvisor", tunStackOption(TunImplementation.GVISOR))
        assertEquals("system", tunStackOption(TunImplementation.SYSTEM))
        assertEquals("mixed", tunStackOption(TunImplementation.MIXED))
    }
}
