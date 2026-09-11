package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.aidl.RequestFlowData
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectionObserver(
    private val snapshot: () -> String,
    private val publish: suspend (RequestFlowBatch) -> Unit,
    private val isCurrent: () -> Boolean,
    private val maps: () -> RequestDisplayMaps,
    private val runtimeGeneration: Long,
    private val intervalMs: Long = 1000L,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val scope: CoroutineScope,
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
    private var lastFingerprint: String? = null
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
                lastFingerprint = null
                if (job?.isActive == true) {
                    scope.launch { pollOnce() }
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
            val parsed = RequestFlowParser.parseSnapshot(snapshot())
            val displayMaps = synchronized(gate) {
                mapsCache ?: maps().also { mapsCache = it }
            }
            val mapped = parsed.map { RequestFlowMapper.map(it, displayMaps) }
            if (!enabled || !isCurrent()) return false
            val fingerprint = fingerprintOf(mapped)
            synchronized(gate) {
                if (!enabled || !isCurrent()) return false
                if (fingerprint == lastFingerprint) return true
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
            pollOnce()
            while (isActive && enabled) {
                wait(intervalMs)
                if (!isActive || !enabled) break
                pollOnce()
            }
            pollActive = false
        }
    }

    private fun stopLocked() {
        enabled = false
        lastFingerprint = null
        publisher.shutdown()
        job?.cancel()
        job = null
        pollActive = false
    }

    companion object {
        fun fingerprintOf(mapped: List<RequestFlowData>): String {
            return mapped.joinToString(separator = "|") { flow ->
                "${flow.id}:${flow.createdAt}:${flow.uploadBytes}:${flow.downloadBytes}:${flow.closed}:${flow.logicalOutbound}:${flow.finalOutboundTag}"
            }
        }
    }
}
