package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import libcore.Libcore

object ConnectionResetDebouncer {
    const val COALESCE_MS = 200L
    const val GO_THROTTLE_MS = 3_000L

    @Volatile var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    @Volatile var performer: () -> Unit = { Libcore.resetAllConnections(true) }
    @Volatile var schedule: (Long, () -> Unit) -> Unit = { delayMs, action ->
        scheduler.schedule({ action() }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "net-recovery").apply { isDaemon = true }
    }

    private val lock = Any()
    private var lastGoOkAt = Long.MIN_VALUE / 4
    private var pending = false
    private var scheduled = false
    private var nextDueAt = 0L

    fun resetForTest() {
        synchronized(lock) {
            lastGoOkAt = Long.MIN_VALUE / 4
            pending = false
            scheduled = false
            nextDueAt = 0L
        }
    }

    fun resetAllConnections(force: Boolean = false) {
        val now = clock()
        synchronized(lock) {
            pending = true
            val goWait = goWaitLocked(now)
            nextDueAt = now + if (force) goWait else maxOf(COALESCE_MS, goWait)
            if (force) scheduled = false
            if (!scheduled) {
                scheduled = true
                val delay = (nextDueAt - now).coerceAtLeast(0L)
                schedule(delay) { onDue() }
            }
        }
    }

    private fun goWaitLocked(now: Long): Long {
        if (lastGoOkAt < Long.MIN_VALUE / 8) return 0L
        return (GO_THROTTLE_MS - (now - lastGoOkAt)).coerceAtLeast(0L)
    }

    private fun onDue() {
        val now = clock()
        val runNow = synchronized(lock) {
            if (now < nextDueAt) {
                val delay = nextDueAt - now
                schedule(delay) { onDue() }
                false
            } else if (!pending) {
                scheduled = false
                false
            } else {
                val wait = goWaitLocked(now)
                if (wait > 0L) {
                    nextDueAt = now + wait
                    schedule(wait) { onDue() }
                    false
                } else {
                    pending = false
                    scheduled = false
                    lastGoOkAt = now
                    true
                }
            }
        }
        if (runNow) {
            Logs.d { "resetAllConnections trailing fire" }
            performer()
        }
    }
}
