package io.nekohasekai.sagernet.ui

object RouterSelectionUiRefresh {
    fun changedIds(previousId: Long, nextId: Long): Set<Long> = buildSet {
        if (previousId > 0L) add(previousId)
        if (nextId > 0L) add(nextId)
    }

    fun needsFullRefresh(visibleIds: Collection<Long>, nextId: Long): Boolean =
        nextId > 0L && nextId !in visibleIds
}
