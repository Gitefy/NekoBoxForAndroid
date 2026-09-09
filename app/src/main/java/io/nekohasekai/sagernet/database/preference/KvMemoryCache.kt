package io.nekohasekai.sagernet.database.preference

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory mirror of a persistent key-value table.
 *
 * Readers ([get]) never touch the database, which lets callers serve preference
 * getters from the main thread after the owning Room database dropped
 * `allowMainThreadQueries`. Durability is delegated to a background writer:
 * [put]/[delete]/[reset] give read-your-writes visibility synchronously and the
 * executor turns the same change into a DB statement as soon as possible.
 *
 * Cross-process merges ([mergeAll]) are guarded by the pending-write set:
 * keys written locally that have not been acknowledged by the DB layer yet,
 * and an in-flight [reset], always win over (possibly stale) database state.
 */
class KvMemoryCache {

    companion object {
        /** Sentinel marker tracked while a reset write is in flight. */
        const val PENDING_RESET = "\u0000kv-reset"
    }

    private val lock = ReentrantReadWriteLock()
    private val values = LinkedHashMap<String, KeyValuePair>()
    private val pendingKeys = HashSet<String>()
    private var pendingReset = false
    @Volatile
    private var primed = false

    /** Whether [prime] ran at least once. */
    val isPrimed: Boolean get() = primed

    /** Replace the mirror with the authoritative table snapshot. */
    fun prime(rows: Collection<KeyValuePair>) = lock.write {
        values.clear()
        pendingReset = false
        for (row in rows) values[row.key] = row
        // Local unacknowledged writes still beat the snapshot just read.
        pendingKeys.removeAll { it == PENDING_RESET }
        primed = true
    }

    /**
     * Merge an authoritative snapshot produced by the DB layer (same or other
     * process). Local in-flight writes are preserved and remain pending until
     * the DB layer acknowledges them.
     */
    fun merge(authoritative: List<KeyValuePair>) {
        if (!primed) {
            prime(authoritative)
            return
        }
        lock.write {
            if (pendingReset) {
                // A local reset is in flight; it wins over remote state until it commits.
                return@write
            }
            val remoteKeys = HashSet<String>(authoritative.size)
            for (row in authoritative) {
                if (row.key !in pendingKeys) values[row.key] = row
                remoteKeys += row.key
            }
            values.keys.removeAll { it !in remoteKeys && it !in pendingKeys }
        }
    }

    fun get(key: String): KeyValuePair? = lock.read { values[key] }

    /** Read-your-writes snapshot for dumps/exports. */
    fun snapshot(): List<KeyValuePair> = lock.read { values.values.toList() }

    fun put(pair: KeyValuePair) = lock.write {
        values[pair.key] = pair
        pendingKeys += pair.key
    }

    fun delete(key: String) = lock.write {
        values.remove(key)
        pendingKeys += key
    }

    fun reset() = lock.write {
        values.clear()
        pendingKeys.clear()
        pendingKeys += PENDING_RESET
        pendingReset = true
    }

    /** Called by the DB layer after the durable write for [key] committed. */
    fun writeCommitted(key: String, value: KeyValuePair?) = lock.write {
        if (key == PENDING_RESET) {
            pendingReset = false
            pendingKeys.remove(PENDING_RESET)
            return@write
        }
        if (pendingKeys.remove(key)) {
            // Re-assert the exact committed value in case a merge raced the commit.
            if (value != null) values[key] = value else values.remove(key)
        }
    }

    fun hasPending(key: String): Boolean = lock.read { key in pendingKeys }
}
