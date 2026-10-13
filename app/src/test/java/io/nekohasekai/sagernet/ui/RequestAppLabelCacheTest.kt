package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestAppLabelCacheMultiPackageTest {
    @Test
    fun resolvesEveryDistinctPackageInACommaSeparatedUidIdentity() {
        val requested = mutableListOf<String>()
        val cache = RequestAppLabelCache { packageName ->
            requested += packageName
            when (packageName) {
                "com.example.one" -> "One"
                "com.example.two" -> "Two"
                else -> null
            }
        }

        assertEquals(2, cache.resolveMissing(listOf("com.example.one, com.example.two,,com.example.one")))
        assertEquals(listOf("com.example.one", "com.example.two"), requested)
        assertEquals("One", cache.display("com.example.one"))
        assertEquals("Two", cache.display("com.example.two"))
    }

    @Test
    fun unknownPackageIsNegativeCachedAndDoesNotCountAsInstalled() {
        var lookups = 0
        val cache = RequestAppLabelCache { packageName -> packageName.also { lookups++ } }

        cache.resolveMissing(listOf("com.example.missing"))
        cache.resolveMissing(listOf("com.example.missing"))

        assertEquals(false, cache.hasResolvedPackages("com.example.missing"))
        assertEquals("com.example.missing", cache.display("com.example.missing"))
        assertEquals(1, lookups)
    }

    @Test
    fun installedPackageWithNoDistinctLabelStillAllowsAppRule() {
        var lookups = 0
        val cache = RequestAppLabelCache(
            resolve = { packageName -> packageName.also { lookups++ } },
            isInstalled = { it == "com.example.installed" },
        )

        assertEquals(1, cache.resolveMissing(listOf("com.example.installed")))
        assertEquals(true, cache.hasResolvedPackages("com.example.installed"))
        assertEquals("com.example.installed", cache.display("com.example.installed"))
        assertEquals(0, cache.resolveMissing(listOf("com.example.installed")))
        assertEquals(1, lookups)
    }
}
