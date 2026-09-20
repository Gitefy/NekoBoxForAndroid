package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class ConnectionObserver(
    private val snapshot: () -> String,
    private val publish: suspend (RequestFlowBatch) -> Unit,
    private val isCurrent: () -> Boolean,
    private val maps: () -> RequestDisplayMaps,
    private val runtimeGeneration: Long,
    private val intervalMs: Long = 1000L,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val scope: CoroutineScope,
    private val revision: (() -> Long?)? = null,
) {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var pollActive: Boolean = false
        private set

    @Volatile
    var lastFailure: String? = null
        private set

    private val gate = Any()
    private var job: Job? = null
    private var loggedFailure = false
    private var mapsCache: RequestDisplayMaps? = null
    private var lastRawSnapshot: String? = null
    private var lastFingerprint: List<RequestSnapshotDedup.Fingerprint>? = null
    private var lastRevision: Long? = null
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val publisher = RequestSnapshotPublisher(
        scope = scope,
        isActive = { enabled && isCurrent() },
        deliver = { batch -> publish(batch) },
    )

    val publisherLaunches: Int
        get() = publisher.launches

    fun setEnabled(value: Boolean) {
        synchronized(gate) {
            if (value) {
                enabled = true
                // New observer session (start, resubscribe, reconnect): first
                // frame must publish even when snapshot X equals the previous session.
                lastRawSnapshot = null
                lastFingerprint = null
                lastRevision = null
                if (job?.isActive == true) {
                    kicks.trySend(Unit)
                } else {
                    startLocked()
                }
            } else {
                stopLocked()
            }
        }
    }

    fun start() = setEnabled(true)

    fun stop() {
        setEnabled(false)
    }

    fun pollOnce(): Boolean {
        if (!enabled || !isCurrent()) return false
        return try {
            val rev = revision?.invoke()
            synchronized(gate) {
                if (!enabled || !isCurrent()) return false
                if (rev != null && lastRevision != null && rev == lastRevision) {
                    return true
                }
            }
            val raw = snapshot()
            synchronized(gate) {
                if (!enabled || !isCurrent()) return false
                if (RequestSnapshotDedup.shouldSkipUnparsed(lastRawSnapshot, raw)) {
                    if (rev != null) lastRevision = rev
                    return true
                }
            }
            val parsed = RequestFlowParser.parseSnapshot(raw)
            val displayMaps = synchronized(gate) {
                mapsCache ?: maps().also { mapsCache = it }
            }
            val mapped = parsed.map { RequestFlowMapper.map(it, displayMaps) }
            if (!enabled || !isCurrent()) return false
            val fingerprint = RequestSnapshotDedup.Fingerprint.listOf(mapped)
            synchronized(gate) {
                if (!enabled || !isCurrent()) return false
                lastRawSnapshot = raw
                if (rev != null) lastRevision = rev
                if (RequestSnapshotDedup.shouldSkipPublish(lastFingerprint, fingerprint)) return true
                lastFingerprint = fingerprint
            }
            publisher.submit(RequestFlowBatch(ArrayList(mapped), runtimeGeneration))
            true
        } catch (e: Exception) {
            lastFailure = e.message
            if (!loggedFailure) {
                loggedFailure = true
                Logs.w("connection snapshot failed")
            }
            false
        }
    }

    private fun startLocked() {
        if (job?.isActive == true) return
        pollActive = true
        job = scope.launch {
            try {
                while (isActive && enabled) {
                    pollOnce()
                    if (!isActive || !enabled) break
                    val waiter = launch { wait(intervalMs) }
                    try {
                        select<Unit> {
                            kicks.onReceive { waiter.cancel() }
                            waiter.onJoin { }
                        }
                    } finally {
                        waiter.cancel()
                    }
                }
            } finally {
                pollActive = false
            }
        }
    }

    private fun stopLocked() {
        enabled = false
        lastRawSnapshot = null
        lastFingerprint = null
        lastRevision = null
        publisher.shutdown()
        job?.cancel()
        job = null
        pollActive = false
    }
}
