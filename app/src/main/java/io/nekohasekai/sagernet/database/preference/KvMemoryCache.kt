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
 * Linearizability of acknowledgments and snapshots:
 *
 * - Every local mutation carries a monotonic generation (the return value of
 *   [put]/[delete]). The DB layer must hand that generation back in
 *   [writeCommitted]; an ACK is only honored for the mutation it confirms, so
 *   a stale ACK can neither regress a newer value nor clear a newer pending
 *   state (nor resurrect a key that was deleted afterwards).
 * - Snapshot merges ([merge]) are guarded by a read epoch captured *before*
 *   the database read ([captureReadEpoch]). Local writes that committed after
 *   that epoch are newer than the snapshot and keep winning, so a snapshot
 *   that was read before a local mutation but merged after its commit cannot
 *   roll the mirror back. Cross-process updates to untouched keys still land.
 *
 * The legacy [merge] overload (no epoch) and the legacy [writeCommitted]
 * overload (no generation) preserve the P01 behavior for callers that cannot
 * capture the token: in-flight pending writes still beat any snapshot, and a
 * legacy ACK is only honored while its content still matches the mirror.
 */
class KvMemoryCache {

    companion object {
        /** Sentinel marker tracked while a reset write is in flight. */
        const val PENDING_RESET = "\u0000kv-reset"
    }

    private val lock = ReentrantReadWriteLock()
    private val values = LinkedHashMap<String, KeyValuePair>()
    private val pendingKeys = HashSet<String>()
    /** Latest un-acknowledged local mutation generation per key. */
    private val pendingGenerations = HashMap<String, Long>()
    /** Generation of the last acknowledged local mutation per key. */
    private val lastCommittedGenerations = HashMap<String, Long>()
    private var generationCounter = 0L
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
     * Epoch token for a database snapshot that is about to be read. Hand the
     * returned value to [merge] together with the snapshot so local mutations
     * that start after this call can never be rolled back by it.
     */
    fun captureReadEpoch(): Long = lock.read { generationCounter }

    /**
     * Merge an authoritative snapshot produced by the DB layer (same or other
     * process). Local in-flight writes are preserved and remain pending until
     * the DB layer acknowledges them. The snapshot's age is unknown, so only
     * pending writes are protected; prefer [merge] with [captureReadEpoch].
     */
    fun merge(authoritative: List<KeyValuePair>) {
        if (!primed) {
            prime(authoritative)
            return
        }
        lock.write { mergeLocked(authoritative, generationCounter) }
    }

    /**
     * Merge a snapshot that was read after [captureReadEpoch] returned
     * [readEpoch]. Local mutations that committed after that epoch are newer
     * than the snapshot and keep winning over it.
     */
    fun merge(authoritative: List<KeyValuePair>, readEpoch: Long) {
        if (!primed) {
            prime(authoritative)
            return
        }
        lock.write { mergeLocked(authoritative, readEpoch) }
    }

    private fun mergeLocked(authoritative: List<KeyValuePair>, readEpoch: Long) {
        if (pendingReset) {
            // A local reset is in flight; it wins over remote state until it commits.
            return
        }
        val remoteKeys = HashSet<String>(authoritative.size)
        for (row in authoritative) {
            remoteKeys += row.key
            if (isLocallyNewer(row.key, readEpoch)) continue
            values[row.key] = row
        }
        values.keys.removeAll { it !in remoteKeys && !isLocallyNewer(it, readEpoch) }
    }

    private fun isLocallyNewer(key: String, readEpoch: Long): Boolean =
        key in pendingKeys || (lastCommittedGenerations[key] ?: Long.MIN_VALUE) > readEpoch

    fun get(key: String): KeyValuePair? = lock.read { values[key] }

    /** Read-your-writes snapshot for dumps/exports; rows are defensive copies. */
    fun snapshot(): List<KeyValuePair> = lock.read { values.values.map(::copiedRow) }

    private fun copiedRow(row: KeyValuePair): KeyValuePair = KeyValuePair(row.key).also { copy ->
        copy.valueType = row.valueType
        copy.value = row.value.copyOf()
    }

    /**
     * Write [pair] into the mirror synchronously and return the generation the
     * DB layer must acknowledge through [writeCommitted] once the write commits.
     */
    fun put(pair: KeyValuePair): Long = lock.write {
        values[pair.key] = pair
        pendingKeys += pair.key
        val generation = ++generationCounter
        pendingGenerations[pair.key] = generation
        generation
    }

    /**
     * Remove [key] from the mirror synchronously and return the generation the
     * DB layer must acknowledge through [writeCommitted] once the delete commits.
     */
    fun delete(key: String): Long = lock.write {
        values.remove(key)
        pendingKeys += key
        val generation = ++generationCounter
        pendingGenerations[key] = generation
        generation
    }

    fun reset() = lock.write {
        values.clear()
        pendingKeys.clear()
        pendingKeys += PENDING_RESET
        pendingGenerations.clear()
        pendingReset = true
    }

    /**
     * Called by the DB layer after the durable write for [key] committed.
     * Only acknowledged when [generation] is still the current pending
     * mutation for the key; superseded ACKs are ignored as stale.
     */
    fun writeCommitted(key: String, value: KeyValuePair?, generation: Long) = lock.write {
        if (key == PENDING_RESET) {
            pendingReset = false
            pendingKeys.remove(PENDING_RESET)
            return@write
        }
        if (pendingGenerations[key] != generation) return@write
        commitLocked(key, value, generation)
    }

    /**
     * Legacy acknowledgment without a generation token. Honored only while the
     * acknowledged content still matches the mirror, so superseded values and
     * resurrection attempts of deleted keys are ignored as stale.
     */
    fun writeCommitted(key: String, value: KeyValuePair?) = lock.write {
        if (key == PENDING_RESET) {
            pendingReset = false
            pendingKeys.remove(PENDING_RESET)
            return@write
        }
        val generation = pendingGenerations[key] ?: return@write
        if (!matchesMirror(key, value)) return@write
        commitLocked(key, value, generation)
    }

    private fun commitLocked(key: String, value: KeyValuePair?, generation: Long) {
        pendingKeys.remove(key)
        pendingGenerations.remove(key)
        lastCommittedGenerations[key] = generation
        // Re-assert the exact committed value in case a merge raced the commit.
        if (value != null) values[key] = value else values.remove(key)
    }

    private fun matchesMirror(key: String, value: KeyValuePair?): Boolean {
        val current = values[key]
        if (value == null) return current == null
        if (current == null) return false
        return current.valueType == value.valueType && current.value.contentEquals(value.value)
    }

    fun hasPending(key: String): Boolean = lock.read { key in pendingKeys }
}