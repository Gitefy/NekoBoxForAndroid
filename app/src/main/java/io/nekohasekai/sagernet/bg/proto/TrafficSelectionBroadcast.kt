package io.nekohasekai.sagernet.bg.proto

/**
 * Deduplicates Router/urltest selection IPC. Comparison is by array **content**,
 * not reference, so a freshly allocated LongArray with the same pairs is treated
 * as unchanged.
 */
object TrafficSelectionBroadcast {
    fun shouldSend(lastSent: LongArray?, next: LongArray): Boolean {
        if (lastSent == null) return true
        return !lastSent.contentEquals(next)
    }

    fun snapshot(next: LongArray): LongArray = next.copyOf()
}
