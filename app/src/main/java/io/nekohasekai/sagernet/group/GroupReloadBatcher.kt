package io.nekohasekai.sagernet.group

import java.util.concurrent.atomic.AtomicLong

/**
 * Coalesces UI reloads during subscription forceResolve so each DNS completion
 * does not rebuild the whole profile list. The first event always fires; later
 * events are limited to [minIntervalMs]; callers must [flush] once at the end.
 */
class GroupReloadBatcher(
    private val minIntervalMs: Long = 300L,
    private val nowMs: () -> Long,
) {
    private val lastReloadAt = AtomicLong(Long.MIN_VALUE / 4)

    fun shouldReloadNow(): Boolean {
        val now = nowMs()
        while (true) {
            val last = lastReloadAt.get()
            if (now - last < minIntervalMs) return false
            if (lastReloadAt.compareAndSet(last, now)) return true
        }
    }

    fun markFlushed() {
        lastReloadAt.set(nowMs())
    }
}
