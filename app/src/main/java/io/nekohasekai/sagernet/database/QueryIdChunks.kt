package io.nekohasekai.sagernet.database

/**
 * Chunks Room `IN (:ids)` queries below SQLite's variable limit.
 * The concatenated list order is not a membership order: callers must
 * `associateBy` id and iterate `router_members` / `userOrder` instead.
 */
object QueryIdChunks {
    const val SIZE = 500

    fun <T> load(ids: Collection<Long>, loadChunk: (List<Long>) -> List<T>): List<T> {
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().chunked(SIZE).flatMap(loadChunk)
    }
}
