package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class CacheCleanupPolicyTest {
    @Test fun fakeIpStateIsPreservedEvenAfterVpnStops() {
        for (running in listOf(false, true)) {
            for (name in listOf("cache.db", "cache.db-wal", "cache.db-shm", "neko.log")) {
                assertTrue(CacheCleanupPolicy.preserve(name, running))
            }
        }
    }
    @Test fun livePluginConfigurationIsPreservedWhileStoppedFilesRemainDisposable() {
        for (name in listOf("tmpcfg", "hysteria_123.ca", "naive_456.crt", "trojan_go_1.json", "auth_2")) {
            assertTrue(CacheCleanupPolicy.preserve(name, true))
            assertFalse(CacheCleanupPolicy.preserve(name, false))
        }
    }
    @Test fun regenerableCacheIsStillDisposable() {
        assertFalse(CacheCleanupPolicy.preserve("download.tmp", true))
        assertFalse(CacheCleanupPolicy.preserve("unrelated.db", true))
    }
}
