package io.nekohasekai.sagernet.fmt

/**
 * First-wins tag → profile id index matching
 * `profileTagMap.filterValues { it == tag }.keys.firstOrNull()`.
 */
object ProfileTagIndex {
    fun byTag(profileTagMap: Map<Long, String>): Map<String, Long> {
        val index = LinkedHashMap<String, Long>(profileTagMap.size)
        for ((id, tag) in profileTagMap) {
            if (tag !in index) index[tag] = id
        }
        return index
    }

    fun idFor(index: Map<String, Long>, tag: String): Long? = index[tag]
}
