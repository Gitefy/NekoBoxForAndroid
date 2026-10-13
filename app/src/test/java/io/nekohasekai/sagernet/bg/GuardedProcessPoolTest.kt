package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedProcessPoolTest {
    @Test
    fun processLogRedactsSeparateAndInlineSecretArguments() {
        val logged = loggedCommand(
            listOf("vela", "run", "--listen", "127.0.0.1:1080", "--key", "private-value",
                "--auth-token=token-value", "--server-key", "public-value")
        )

        assertTrue(logged.contains("--listen 127.0.0.1:1080"))
        assertTrue(logged.contains("--key <redacted>"))
        assertTrue(logged.contains("--auth-token=<redacted>"))
        assertFalse(logged.contains("private-value"))
        assertFalse(logged.contains("token-value"))
        assertFalse(logged.contains("public-value"))
    }
}
