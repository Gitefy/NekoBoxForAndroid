package io.nekohasekai.sagernet.ui

object ConfigurationListFilter {
    fun matches(query: String, displayName: String, displayType: String, displayAddress: String): Boolean {
        if (query.isEmpty()) return true
        return displayName.contains(query, ignoreCase = true) ||
            displayType.contains(query, ignoreCase = true) ||
            displayAddress.contains(query, ignoreCase = true)
    }

    fun shouldNotify(previousIds: List<Long>, nextIds: List<Long>): Boolean =
        previousIds != nextIds

    /**
     * Empty query restores the unfiltered list via reload. Skip when we are
     * already showing that unfiltered list ([lastQuery] is the empty string).
     * A null [lastQuery] means filter has not run yet, so reload stays required.
     */
    fun shouldReloadUnfiltered(lastQuery: String?, newQuery: String): Boolean {
        if (newQuery.isNotEmpty()) return false
        return lastQuery != ""
    }
}
