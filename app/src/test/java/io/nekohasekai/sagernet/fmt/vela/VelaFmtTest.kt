package io.nekohasekai.sagernet.fmt.vela

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VelaFmtTest {
    @Test
    fun uriRoundTripPreservesEndpointKeysAndName() {
        val source = VelaBean().apply {
            serverAddress = "vela.example.org"
            serverPort = 8443
            clientPrivateKey = "11".repeat(32)
            serverPublicKey = "ab".repeat(32)
            name = "home node"
        }

        val result = parseVela(source.toUri())

        assertEquals(source.serverAddress, result.serverAddress)
        assertEquals(source.serverPort, result.serverPort)
        assertEquals(source.clientPrivateKey, result.clientPrivateKey)
        assertEquals(source.serverPublicKey, result.serverPublicKey)
        assertEquals(source.name, result.name)
    }

    @Test
    fun rejectsMalformedOrMissingKeyMaterial() {
        val bean = VelaBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 8443
            clientPrivateKey = "not-a-key"
            serverPublicKey = "ab".repeat(32)
        }

        assertThrows(IllegalArgumentException::class.java) { bean.validate() }
        assertThrows(IllegalArgumentException::class.java) {
            parseVela("vela://127.0.0.1:8443?client_key=bad&server_key=${"ab".repeat(32)}")
        }
    }

    @Test
    fun beanDiagnosticsDoNotRevealKeyMaterial() {
        val privateKey = "11".repeat(32)
        val publicKey = "ab".repeat(32)
        val bean = VelaBean().apply {
            clientPrivateKey = privateKey
            serverPublicKey = publicKey
        }

        val diagnostic = bean.toString()

        org.junit.Assert.assertFalse(diagnostic.contains(privateKey))
        org.junit.Assert.assertFalse(diagnostic.contains(publicKey))
        org.junit.Assert.assertTrue(diagnostic.contains("<redacted>"))
    }
}
