package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** Kind of a persisted (or persisted-then-failed) operation. */
enum class WriteOperationKind { PUT, DELETE, RESET, RESTORE }

/**
 * Admission-order ticket. [queueSequence] is the store-local writer clock that
 * orders admission/barrier/fence operations; [cacheGeneration] is the
 * [KvMemoryCache] generation token used only for stale-ACK protection and is
 * independent of [queueSequence]; [writeEpoch] is only a local write fence.
 */
data class WriteTicket(
    val queueSequence: Long,
    val writeEpoch: Long,
    val cacheGeneration: Long?,
)

/** A failed operation. Whole-table operations carry a null [key]. */
data class WriteFailure(
    val operation: WriteOperationKind,
    val key: String?,
    val reason: String,
)

/** Result of [RoomPreferenceDataStore.flushPendingWrites]/[reset]/[restore]. */
data class FlushResult(
    val success: Boolean,
    val completed: Int,
    val failures: List<WriteFailure>,
)

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
 * S1-B3 adds an explicit write barrier and whole-table fencing:
 *
 * - [flushPendingWrites] inserts an in-band marker into the same FIFO; it runs
 *   only after all operations admitted before the cut reached a terminal state
 *   and evaluates the cut-scoped effective durable state (a failed key is
 *   recoverable only by a later successful same-key write admitted before the
 *   same cut; a failed whole-table fence cannot be healed by keyed writes).
 * - [restore]/[reset] are whole-table fences: they go through the same writer,
 *   run inside the configured transaction runner, hold [snapshotLock] from the
 *   transaction start through the cache commit/abort, and only return after
 *   the durable terminal state.
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
    private val restoreTransaction: (() -> Unit) -> Unit = { it() },
) : PreferenceDataStore() {

    private val cache = KvMemoryCache()
    private val snapshotLock = ReentrantLock()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kv-store-writer").apply { isDaemon = true }
    }

    // Coordinator state. All ledger slots are guarded by [ledgerLock]; the
    // writer thread is the only place that advances terminals (plus the rare
    // admission-time rejection path), so a marker evaluating the slots at its
    // FIFO-ordered execution time sees exactly the cut state. No operation
    // journal is kept: one slot per key, one whole-table slot, the counter and
    // the queue.
    private val admissionLock = ReentrantLock()
    private val ledgerLock = Any()
    private var queueSequence = 0L
    private var writeEpoch = 0L
    private var terminalMutationCount = 0L

    private class KeyedOutcome(
        val queueSequence: Long,
        val operation: WriteOperationKind,
        val success: Boolean,
        val reason: String?,
    )

    private class WholeTableOutcome(
        val queueSequence: Long,
        val operation: WriteOperationKind,
        val success: Boolean,
        val reason: String?,
    )

    private val latestKeyedOutcome = HashMap<String, KeyedOutcome>()
    private var latestWholeTableOutcome: WholeTableOutcome? = null

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
     * Serialize the complete snapshot path. Without this coordinator two
     * concurrent readers can interleave as A(capture)→A(read old)→B(capture)→
     * B(read new)→B(merge new)→A(merge old) and regress the mirror to a
     * stale DB state. Holding one lock across capture+read+merge preserves
     * per-key generation semantics, needs no sleep/delay, and keeps the
     * single-writer and retry behavior unchanged. Whole-table fences hold the
     * same lock from the transaction start through the cache commit/abort so
     * a snapshot that read the DB before a fence cannot merge after it.
     */
    private fun readAndMergeSnapshot() = snapshotLock.withLock {
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

    internal fun flushPendingWritesAsync(): CompletableFuture<FlushResult> =
        admissionLock.withLock {
            val cut = ++queueSequence
            val future = CompletableFuture<FlushResult>()
            try {
                writer.execute { evaluateBarrierMarker(cut, future) }
            } catch (e: RejectedExecutionException) {
                future.completeExceptionally(e)
            }
            future
        }

    /**
     * Wait until every operation admitted before this call reached a durable
     * terminal state, evaluated at the cutoff (cut-scoped effective durable
     * state). Operations admitted afterwards cannot extend or heal this
     * barrier; cancelling the waiter never cancels admitted writes.
     */
    suspend fun flushPendingWrites(): FlushResult = awaitSuspendable(flushPendingWritesAsync())

    /**
     * Whole-table fence: replace the settings table with [rows] in one
     * transaction and block the (off-main) caller until the durable result.
     * Queued pre-fence writes still land first and are erased by the
     * transaction's reset, so they cannot repollute the restored database.
     */
    fun restore(rows: List<KeyValuePair>): FlushResult {
        val frozen = rows.map { copyRow(it) }
        val future = admissionLock.withLock {
            val seq = ++queueSequence
            val epoch = ++writeEpoch
            val fenceToken = cache.beginFullTableFence(frozen)
            val ticket = WriteTicket(seq, epoch, null)
            val future = CompletableFuture<FlushResult>()
            try {
                writer.execute {
                    executeWholeTableTask(ticket, WriteOperationKind.RESTORE, frozen, fenceToken, future)
                }
            } catch (e: RejectedExecutionException) {
                cache.abortFullTableFence(fenceToken)
                future.complete(rejectedWholeTableResult(ticket, WriteOperationKind.RESTORE))
            }
            future
        }
        return try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Settings restore await was interrupted; the write continues in background", e)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /**
     * Whole-table fence that clears the settings table, blocks until the
     * durable result, and reports failure instead of pretending success.
     */
    suspend fun reset(): FlushResult {
        val future = admissionLock.withLock {
            val seq = ++queueSequence
            val epoch = ++writeEpoch
            val fenceToken = cache.beginFullTableFence(emptyList())
            val ticket = WriteTicket(seq, epoch, null)
            val future = CompletableFuture<FlushResult>()
            try {
                writer.execute {
                    executeWholeTableTask(ticket, WriteOperationKind.RESET, emptyList(), fenceToken, future)
                }
            } catch (e: RejectedExecutionException) {
                cache.abortFullTableFence(fenceToken)
                future.complete(rejectedWholeTableResult(ticket, WriteOperationKind.RESET))
            }
            future
        }
        return awaitSuspendable(future)
    }

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
        val ticket = admissionLock.withLock {
            val previous = cache.get(key)
            val generation = cache.delete(key)
            WriteTicket(++queueSequence, writeEpoch, generation).also { ticket ->
                try {
                    writer.execute { executeDeleteWithRetry(key, ticket) }
                } catch (e: RejectedExecutionException) {
                    cache.revertPendingMutation(key, generation, previous)
                    reportRejectedKeyed(ticket, WriteOperationKind.DELETE, key)
                }
            }
        }
        fireChangeListener(key)
    }

    private fun putValue(key: String, pair: KeyValuePair) {
        val frozen = copyRow(pair)
        val ticket = admissionLock.withLock {
            val previous = cache.get(key)
            val generation = cache.put(frozen)
            WriteTicket(++queueSequence, writeEpoch, generation).also { ticket ->
                try {
                    writer.execute { executePutWithRetry(key, frozen, ticket) }
                } catch (e: RejectedExecutionException) {
                    cache.revertPendingMutation(key, generation, previous)
                    reportRejectedKeyed(ticket, WriteOperationKind.PUT, key)
                }
            }
        }
        fireChangeListener(key)
    }

    private val putRetryDelaysMs = longArrayOf(50L, 100L, 200L)

    private fun executePutWithRetry(key: String, pair: KeyValuePair, ticket: WriteTicket) {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                kvPairDao.put(pair)
                cache.writeCommitted(key, pair, ticket.cacheGeneration!!)
                ledgerKeyedTerminal(ticket, WriteOperationKind.PUT, key, true, null)
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
        ledgerKeyedTerminal(ticket, WriteOperationKind.PUT, key, false, reasonOf(lastError))
        Logs.w { "Giving up persisting preference $key after 3 attempts; the failure is visible to flushPendingWrites" }
    }

    private fun executeDeleteWithRetry(key: String, ticket: WriteTicket) {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                kvPairDao.delete(key)
                cache.writeCommitted(key, null, ticket.cacheGeneration!!)
                ledgerKeyedTerminal(ticket, WriteOperationKind.DELETE, key, true, null)
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
        ledgerKeyedTerminal(ticket, WriteOperationKind.DELETE, key, false, reasonOf(lastError))
        Logs.w { "Giving up deleting preference $key after 3 attempts; the failure is visible to flushPendingWrites" }
    }

    private fun executeWholeTableTask(
        ticket: WriteTicket,
        operation: WriteOperationKind,
        rows: List<KeyValuePair>,
        fenceToken: Long,
        future: CompletableFuture<FlushResult>,
    ) {
        var failureReason: String? = null
        snapshotLock.withLock {
            try {
                restoreTransaction {
                    kvPairDao.reset()
                    if (rows.isNotEmpty()) kvPairDao.insert(rows)
                }
                cache.commitFullTableFence(fenceToken, rows)
            } catch (e: Exception) {
                failureReason = reasonOf(e)
                runCatching { cache.abortFullTableFence(fenceToken) }.onFailure {
                    Logs.w(it) { "Failed to abort whole-table fence $fenceToken" }
                }
                Logs.w(e) { "Whole-table $operation failed; holding optimistic mirror until the ordered realign" }
                // Ordered fresh read at the same writer position: realigns the
                // mirror with the actual table after the failed transaction and
                // preserves post-fence keyed pendings (merge skips pending keys).
                runCatching { readAndMergeSnapshot() }.onFailure {
                    Logs.w(it) { "Post-failure mirror realign failed; next invalidation will retry" }
                }
            }
        }
        val result = synchronized(ledgerLock) {
            terminalMutationCount++
            latestWholeTableOutcome = WholeTableOutcome(ticket.queueSequence, operation, failureReason == null, failureReason)
            if (failureReason == null) latestKeyedOutcome.clear()
            FlushResult(
                failureReason == null,
                terminalMutationCount.toInt(),
                if (failureReason == null) emptyList() else listOf(WriteFailure(operation, null, failureReason)),
            )
        }
        future.complete(result)
    }

    /** In-band barrier marker: runs at FIFO position, evaluates the cut, never blocks the writer. */
    private fun evaluateBarrierMarker(cut: Long, future: CompletableFuture<FlushResult>) {
        val result = synchronized(ledgerLock) {
            val failures = ArrayList<WriteFailure>()
            val fence = latestWholeTableOutcome
            if (fence != null && fence.queueSequence < cut && !fence.success) {
                failures += WriteFailure(fence.operation, null, fence.reason ?: "whole-table ${fence.operation.name} failed")
            } else {
                for ((key, outcome) in latestKeyedOutcome) {
                    if (outcome.queueSequence < cut && !outcome.success) {
                        failures += WriteFailure(outcome.operation, key, outcome.reason ?: "persistence failed")
                    }
                }
            }
            FlushResult(failures.isEmpty(), terminalMutationCount.toInt(), failures)
        }
        future.complete(result)
    }

    private fun ledgerKeyedTerminal(
        ticket: WriteTicket,
        operation: WriteOperationKind,
        key: String,
        success: Boolean,
        reason: String?,
    ) = synchronized(ledgerLock) {
        terminalMutationCount++
        latestKeyedOutcome[key] = KeyedOutcome(ticket.queueSequence, operation, success, reason)
    }

    private fun reportRejectedKeyed(ticket: WriteTicket, operation: WriteOperationKind, key: String) =
        ledgerKeyedTerminal(ticket, operation, key, false, "RejectedExecutionException")

    private fun rejectedWholeTableResult(ticket: WriteTicket, operation: WriteOperationKind): FlushResult =
        synchronized(ledgerLock) {
            terminalMutationCount++
            latestWholeTableOutcome = WholeTableOutcome(ticket.queueSequence, operation, false, "RejectedExecutionException")
            FlushResult(false, terminalMutationCount.toInt(), listOf(WriteFailure(operation, null, "RejectedExecutionException")))
        }

    private suspend fun awaitSuspendable(future: CompletableFuture<FlushResult>): FlushResult = try {
        runInterruptible(Dispatchers.IO) { future.get() }
    } catch (e: ExecutionException) {
        throw (e.cause ?: e)
    }

    /** Sanitized reason: exception category only, never values or raw messages. */
    private fun reasonOf(e: Throwable?): String = e?.javaClass?.name ?: "persistence failed"

    private fun copyRow(pair: KeyValuePair): KeyValuePair = KeyValuePair(pair.key).also { copy ->
        copy.valueType = pair.valueType
        copy.value = pair.value.copyOf()
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