package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class PanelAuthenticationTest {
    @Test fun onlyBundledLoopbackPanelReceivesCredential() {
        for (url in listOf(
            "https://example.com/ui/", "http://localhost:9090/ui/",
            "http://127.0.0.1:9091/ui/", "http://127.0.0.1:9090.evil/ui/",
            "http://127.0.0.1:9090/ui.evil/", "http://user@127.0.0.1:9090/ui/",
            "http://127.0.0.1:9090/ui/../remote", "not a URI",
            "http://127.0.0.1:9090/ui/%2e%2e/remote",
        )) assertNull(url, localPanelUrl(url))
        assertEquals("http://127.0.0.1:9090/ui/", localPanelUrl("http://127.0.0.1:9090/ui/"))
        assertEquals("http://127.0.0.1:9090/ui/?theme=dark#/proxies", localPanelUrl(
            "http://127.0.0.1:9090/ui/?hostname=evil.example&port=443&secret=old&theme=dark#/proxies",
        ))
    }
    @Test fun bootstrapEscapesScriptInjectionAndNeverNavigatesToRemoteBackend() {
        val html = panelBootstrap("</script><script>alert('secret')</script>")
        assertEquals(1, "</script>".toRegex().findAll(html).count())
        assertTrue(html.contains("\\u003c"))
        assertTrue(html.contains("baseURL: 'http://127.0.0.1:9090'"))
        assertFalse(html.contains("location"))
    }
}
