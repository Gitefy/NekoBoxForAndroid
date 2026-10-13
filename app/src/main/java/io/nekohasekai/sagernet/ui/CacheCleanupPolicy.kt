package io.nekohasekai.sagernet.ui

internal object CacheCleanupPolicy {
    fun preserve(name: String, running: Boolean): Boolean =
        name in setOf("neko.log", "cache.db", "cache.db-wal", "cache.db-shm") ||
            running && (name == "tmpcfg" || listOf("trojan_go_", "mieru_", "naive_", "hysteria_", "tuic_", "cert_", "auth_").any(name::startsWith))
}
