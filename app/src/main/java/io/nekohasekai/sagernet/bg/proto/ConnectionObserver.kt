package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectionObserver(
    private val snapshot: () -> String,
    private val publish: (RequestFlowBatch) -> Unit,
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

    private var job: Job? = null
    private var loggedFailure = false

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) startLocked() else stopLocked()
    }

    fun start() = setEnabled(true)

    fun stop() {
        setEnabled(false)
    }

    fun pollOnce(): Boolean {
        if (!isCurrent()) return false
        return try {
            val parsed = RequestFlowParser.parseSnapshot(snapshot())
            val displayMaps = maps()
            val mapped = parsed.map { RequestFlowMapper.map(it, displayMaps) }
            if (!isCurrent()) return false
            publish(RequestFlowBatch(ArrayList(mapped), runtimeGeneration))
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
        job?.cancel()
        job = null
        pollActive = false
    }
}
