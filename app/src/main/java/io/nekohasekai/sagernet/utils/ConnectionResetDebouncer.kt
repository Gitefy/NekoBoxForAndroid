package io.nekohasekai.sagernet.utils

import android.os.SystemClock
import io.nekohasekai.sagernet.ktx.Logs
import libcore.Libcore

/**
 * App-side debounce for [Libcore.resetAllConnections].
 *
 * Network capability flaps and wake events can fire several times per second;
 * the Go core already throttles to 3s ([libcore/box.go]), but collapsing the
 * burst before the JNI crossing avoids extra wakeups and log spam.
 */
object ConnectionResetDebouncer {
    private const val DEBOUNCE_MS = 2000L
    @Volatile
    private var lastResetUptime = 0L

    @Synchronized
    fun resetAllConnections() {
        val now = SystemClock.elapsedRealtime()
        val last = lastResetUptime
        if (last != 0L && now - last < DEBOUNCE_MS) {
            Logs.d { "resetAllConnections debounced" }
            return
        }
        lastResetUptime = now
        Libcore.resetAllConnections(true)
    }
}
