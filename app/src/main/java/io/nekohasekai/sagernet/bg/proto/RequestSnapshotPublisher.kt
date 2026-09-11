package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class RequestSnapshotPublisher(
    private val scope: CoroutineScope,
    private val isActive: () -> Boolean,
    private val deliver: suspend (RequestFlowBatch) -> Unit,
) {
    private val latest = AtomicReference<RequestFlowBatch?>(null)
    private val lock = Any()
    private var worker: Job? = null

    @Volatile
    var launches: Int = 0
        private set

    fun submit(batch: RequestFlowBatch) {
        latest.set(batch)
        synchronized(lock) {
            if (worker?.isActive == true) return
            launches++
            worker = scope.launch { drain() }
        }
    }

    fun shutdown() {
        latest.set(null)
        synchronized(lock) {
            worker?.cancel()
            worker = null
        }
    }

    private suspend fun drain() {
        while (true) {
            val batch = latest.getAndSet(null) ?: break
            if (!isActive()) break
            deliver(batch)
        }
        synchronized(lock) {
            worker = null
            if (latest.get() != null && isActive()) {
                launches++
                worker = scope.launch { drain() }
            }
        }
    }
}
