package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RequestRuleFactory

class RequestAppLabelCache(
    private val maxEntries: Int = 256,
    private val isInstalled: (String) -> Boolean = { false },
    private val resolve: (String) -> String?,
) {
    private data class Resolution(val installed: Boolean, val label: String?)

    private val labels = object : LinkedHashMap<String, Resolution>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resolution>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun peek(packageName: String): String? = labels[packageName]?.label

    fun display(packageName: String): String {
        if (packageName.isBlank()) return ""
        return peek(packageName)?.takeIf { it.isNotBlank() } ?: packageName
    }

    fun displayAll(packageNames: String): List<String> =
        RequestRuleFactory.parsePackageNames(packageNames).map { packageName ->
            val label = display(packageName)
            if (label == packageName) packageName else "$label ($packageName)"
        }

    @Synchronized
    fun hasResolvedPackages(packageNames: String): Boolean {
        val packages = RequestRuleFactory.parsePackageNames(packageNames)
        return packages.isNotEmpty() && packages.all { labels[it]?.installed == true }
    }

    @Synchronized
    fun remember(packageName: String, label: String) {
        if (packageName.isBlank()) return
        labels[packageName] = Resolution(installed = true, label = label.takeIf { it.isNotBlank() && it != packageName })
    }

    fun resolveMissing(packages: Collection<String>): Int {
        var misses = 0
        packages.flatMap(RequestRuleFactory::parsePackageNames).distinct().forEach { pkg ->
            if (pkg.isBlank() || isCached(pkg)) return@forEach
            misses++
            val resolvedLabel = resolve(pkg)
            val installed = isInstalled(pkg) || (!resolvedLabel.isNullOrBlank() && resolvedLabel != pkg)
            synchronized(this) {
                labels[pkg] = Resolution(
                    installed = installed,
                    label = resolvedLabel?.takeIf { it.isNotBlank() && it != pkg },
                )
            }
        }
        return misses
    }

    @Synchronized
    private fun isCached(packageName: String): Boolean = labels.containsKey(packageName)

    @Synchronized
    fun clear() {
        labels.clear()
    }
}
