package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withLockSuspend

enum class WriteOperationKind { PUT, DELETE, RESET, RESTORE }

data class WriteTicket(
    val queueSequence: Long,
    val writeEpoch: Long,
    val cacheGeneration: Long?,
)

data class WriteFailure(
    val operation: WriteOperationKind,
    val key: String?,
    val reason: String,
)

data class FlushResult(
    val success: Boolean,
    val completed: Int,
    val failures: List<WriteFailure>,
)

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

    sealed interface StoreReadiness {
        object Loading : StoreReadiness
        object Ready : StoreReadiness
        data class Failed(val errorCode: String) : StoreReadiness
    }

    private val readinessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readinessFlow = MutableStateFlow<StoreReadiness>(StoreReadiness.Loading)
    private val retryMutex = Mutex()
    @Volatile private var primeJob: Job? = null
    private val bootDirty = AtomicBoolean(false)
    @Volatile private var bootstrapDone = false
    val readiness: StoreReadiness get() = readinessFlow.value
    fun isReady(): Boolean = readinessFlow.value is StoreReadiness.Ready
    suspend fun awaitReady(): StoreReadiness = readinessFlow.first { it !is StoreReadiness.Loading }
    suspend fun retryPrime(): StoreReadiness {
        if (readinessFlow.value is StoreReadiness.Ready) return readinessFlow.value
        retryMutex.withLockSuspend {
            when (readinessFlow.value) {
                is StoreReadiness.Ready -> return@withLockSuspend
                is StoreReadiness.Loading -> {
                    primeJob?.join()
                    return@withLockSuspend
                }
                is StoreReadiness.Failed -> {
                    bootDirty.set(false)
                    readinessFlow.value = StoreReadiness.Loading
                    launchPrime()
                }
            }
        }
        return awaitReady()
    }

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
        invalidationSource?.onInvalidate { tables ->
            if ("KeyValuePair" in tables) {
                if (!bootstrapDone) bootDirty.set(true)
                else runCatching { readAndMergeSnapshot() }.onFailure { Logs.w(it) }
            }
        }
        readinessFlow.value = StoreReadiness.Loading
        launchPrime()
    }

    private fun launchPrime() {
        val job = readinessScope.launch {
            val primeResult = runCatching { readAndMergeSnapshot() }
            if (primeResult.isFailure) {
                val e = primeResult.exceptionOrNull()!!
                Logs.w(e) { "Store prime failed; entering Failed state" }
                readinessFlow.value = StoreReadiness.Failed(e.javaClass.simpleName.takeIf { it.isNotBlank() } ?: "StorePrimeFailed")
                return@launch
            }
            var catchUpError: Throwable? = null
            snapshotLock.withLock {
                fun catchUp(): Boolean {
                    while (bootDirty.compareAndSet(true, false)) {
                        try {
                            val epoch = cache.captureReadEpoch()
                            cache.merge(tableSnapshot(), epoch)
                        } catch (e: Throwable) {
                            Logs.w(e) { "bootstrap catchup read failed" }
                            catchUpError = e
                            return false
                        }
                    }
                    return true
                }
                if (!catchUp()) {
                    bootstrapDone = false
                    return@withLock
                }
                bootstrapDone = true
                if (!catchUp()) {
                    bootstrapDone = false
                    return@withLock
                }
                readinessFlow.value = StoreReadiness.Ready
            }
            catchUpError?.let { e ->
                readinessFlow.value = StoreReadiness.Failed(
                    e.javaClass.simpleName.takeIf { it.isNotBlank() } ?: "StorePrimeFailed",
                )
            }
        }
        primeJob = job
    }

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

    fun cachedAll(): List<KeyValuePair> = cache.snapshot()
    fun readCommittedSettingsSnapshot(): List<KeyValuePair> = tableSnapshot().map(::copyRow)
    suspend fun readCommittedSettingsSnapshotOffMain(): List<KeyValuePair> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { readCommittedSettingsSnapshot() }

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

    suspend fun flushPendingWrites(): FlushResult = awaitSuspendable(flushPendingWritesAsync())

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

    private fun reasonOf(e: Throwable?): String = e?.javaClass?.name ?: "persistence failed"

    private fun copyRow(pair: KeyValuePair): KeyValuePair = KeyValuePair(pair.key).also { copy ->
        copy.valueType = pair.valueType
        copy.value = pair.value.copyOf()
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) { listeners.toList() }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun interface InvalidationSource {
        fun onInvalidate(observe: (tables: Set<String>) -> Unit)
    }
}
