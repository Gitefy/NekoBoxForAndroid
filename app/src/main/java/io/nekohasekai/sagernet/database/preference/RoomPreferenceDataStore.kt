package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PreferenceDataStore backed by a Room `KeyValuePair` table.
 *
 * Since Android 16 targets must not touch SQLite on the main thread, the store
 * keeps a [KvMemoryCache] mirror of the table:
 *
 * - Reads are served from memory only, so preference getters are safe from any
 *   thread without `allowMainThreadQueries`.
 * - Writes update the mirror synchronously (read-your-writes for every later
 *   getter, including listener callbacks) and are persisted FIFO on a single
 *   writer thread.
 * - The owning Room database observes `KeyValuePair` invalidations on its own
 *   thread (own-process commits and multi-instance invalidation from the other
 *   process) and merges them into the same mirror; local in-flight writes stay
 *   authoritative until the DB layer acknowledges them.
 *
 * The mirror is primed once at store construction. That single blocking table
 * read replaces the previous per-read synchronous queries and only ever runs
 * during process startup, before any UI is drawn.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(
    private val kvPairDao: KeyValuePair.Dao,
    private val invalidationSource: InvalidationSource? = null,
    private val tableSnapshot: () -> List<KeyValuePair> = kvPairDao::all,
) : PreferenceDataStore() {

    private val cache = KvMemoryCache()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kv-store-writer").apply { isDaemon = true }
    }

    init {
        // One-time mirror prime; replaces per-read synchronous queries.
        runBlocking(Dispatchers.IO) {
            runCatching { readAndMergeSnapshot() }.onFailure { Logs.w(it) }
        }
        invalidationSource?.onInvalidate { tables ->
            if ("KeyValuePair" in tables) {
                runCatching { readAndMergeSnapshot() }.onFailure { Logs.w(it) }
            }
        }
    }

    /**
     * Capture the mirror's mutation epoch before the table read so [merge]
     * can reject snapshots that predate local commits made afterwards.
     */
    private fun readAndMergeSnapshot() {
        val readEpoch = cache.captureReadEpoch()
        cache.merge(tableSnapshot(), readEpoch)
    }

    fun getBoolean(key: String) = cache.get(key)?.boolean
    fun getFloat(key: String) = cache.get(key)?.float
    fun getInt(key: String) = cache.get(key)?.long?.toInt()
    fun getLong(key: String) = cache.get(key)?.long
    fun getString(key: String) = cache.get(key)?.string
    fun getStringSet(key: String) = cache.get(key)?.stringSet

    /** Authoritative mirror snapshot; no database access. */
    fun cachedAll(): List<KeyValuePair> = cache.snapshot()

    /**
     * Re-read the whole (small) table into the mirror. Call before reading
     * settings that may have been written by the other process and whose
     * invalidation may not have propagated yet (service start/reload).
     */
    suspend fun syncNow() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        readAndMergeSnapshot()
    }

    /**
     * Restore path. Runs inside `Dispatchers.Default` work (BackupFragment),
     * so a blocking dispatcher hop is cheaper than restructuring the whole
     * caller chain into suspend.
     */
    fun restore(rows: List<KeyValuePair>) {
        cache.prime(rows)
        runBlocking(Dispatchers.IO) {
            kvPairDao.reset()
            if (rows.isNotEmpty()) kvPairDao.insert(rows)
            cache.merge(kvPairDao.all())
        }
    }

    fun reset() = kvPairDao.reset()

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) =
        getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) =
        if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) =
        if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)
    override fun putBoolean(key: String, value: Boolean) {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putFloat(key: String, value: Float) {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putInt(key: String, value: Int) {
        putValue(key, KeyValuePair(key).put(value.toLong()))
    }

    override fun putLong(key: String, value: Long) {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putString(key: String, value: String?) = if (value == null) remove(key) else {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        if (values == null) remove(key) else {
            putValue(key, KeyValuePair(key).put(values))
        }

    fun remove(key: String) {
        val generation = cache.delete(key)
        fireChangeListener(key)
        writer.execute { executeDeleteWithRetry(key, generation) }
    }

    private fun putValue(key: String, pair: KeyValuePair) {
        val generation = cache.put(pair)
        fireChangeListener(key)
        writer.execute { executePutWithRetry(key, pair, generation) }
    }

    private val putRetryDelaysMs = longArrayOf(50L, 100L, 200L)

    private fun executePutWithRetry(key: String, pair: KeyValuePair, generation: Long) {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                kvPairDao.put(pair)
                cache.writeCommitted(key, pair, generation)
                return
            } catch (e: Exception) {
                lastError = e
                Logs.w(e) { "Failed to persist preference $key (attempt ${attempt + 1}/3)" }
                if (attempt < 2) {
                    try {
                        Thread.sleep(putRetryDelaysMs[attempt])
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        if (lastError != null) Logs.w(lastError) { "Giving up persisting preference $key after 3 attempts; keeping memory value, will retry on next put" }
        else Logs.w { "Giving up persisting preference $key after 3 attempts; keeping memory value, will retry on next put" }
    }

    private fun executeDeleteWithRetry(key: String, generation: Long) {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                kvPairDao.delete(key)
                cache.writeCommitted(key, null, generation)
                return
            } catch (e: Exception) {
                lastError = e
                Logs.w(e) { "Failed to delete preference $key (attempt ${attempt + 1}/3)" }
                if (attempt < 2) {
                    try {
                        Thread.sleep(putRetryDelaysMs[attempt])
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        if (lastError != null) Logs.w(lastError) { "Giving up deleting preference $key after 3 attempts; keeping memory tombstone" }
        else Logs.w { "Giving up deleting preference $key after 3 attempts; keeping memory tombstone" }
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Change-feed from the owning Room database. Production sources subscribe
     * on the database's own invalidation thread; the observer must never run
     * on the main thread because a mirror refresh still performs a table read.
     */
    fun interface InvalidationSource {
        fun onInvalidate(observe: (tables: Set<String>) -> Unit)
    }
}
