package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RequestSnapshotPublisher(
    private val scope: CoroutineScope,
    private val isActive: () -> Boolean,
    private val deliver: suspend (RequestFlowBatch) -> Unit,
) {
    private val latest = AtomicReference<RequestFlowBatch?>(null)
    private val generation = AtomicInteger(0)
    private val lock = Any()
    private var worker: Job? = null
    private var workerGen: Int = 0

    @Volatile
    var launches: Int = 0
        private set

    fun submit(batch: RequestFlowBatch) {
        latest.set(batch)
        synchronized(lock) {
            if (worker?.isActive == true) return
            startWorkerLocked()
        }
    }

    fun shutdown() {
        generation.incrementAndGet()
        latest.set(null)
        synchronized(lock) {
            worker?.cancel()
            worker = null
        }
    }

    private fun startWorkerLocked() {
        val gen = generation.get()
        launches++
        workerGen = gen
        worker = scope.launch { drain(gen) }
    }

    private suspend fun drain(gen: Int) {
        try {
            while (generation.get() == gen) {
                val batch = latest.getAndSet(null) ?: break
                if (generation.get() != gen) {
                    latest.compareAndSet(null, batch)
                    break
                }
                if (!isActive()) break
                deliver(batch)
            }
        } finally {
            synchronized(lock) {
                if (workerGen != gen) return@synchronized
                worker = null
                if (generation.get() == gen && latest.get() != null && isActive()) {
                    startWorkerLocked()
                }
            }
        }
    }
}
