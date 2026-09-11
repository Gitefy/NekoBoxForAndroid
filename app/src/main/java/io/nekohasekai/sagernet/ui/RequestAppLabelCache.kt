package io.nekohasekai.sagernet.ui

class RequestAppLabelCache(
    private val maxEntries: Int = 256,
    private val resolve: (String) -> String?,
) {
    private val labels = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun peek(packageName: String): String? = labels[packageName]

    fun display(packageName: String): String {
        if (packageName.isBlank()) return ""
        return peek(packageName) ?: packageName
    }

    @Synchronized
    fun remember(packageName: String, label: String) {
        if (packageName.isBlank() || label.isBlank()) return
        labels[packageName] = label
    }

    fun resolveMissing(packages: Collection<String>): Int {
        var misses = 0
        packages.forEach { raw ->
            val pkg = raw.substringBefore(',')
            if (pkg.isBlank() || peek(pkg) != null) return@forEach
            misses++
            val label = resolve(pkg) ?: return@forEach
            remember(pkg, label)
        }
        return misses
    }

    @Synchronized
    fun clear() {
        labels.clear()
    }
}
