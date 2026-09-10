package io.nekohasekai.sagernet.ktx

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Execute a blocking Room DAO call off the main thread.
 *
 * - On the **main thread**: hops to [Dispatchers.IO] via [runBlocking] and
 *   blocks briefly. Prefer a proper `suspend` DAO or an explicit
 *   `withContext(Dispatchers.IO)` for hot paths — this helper is the
 *   last-resort guard for one-shot bootstrap reads (e.g. empty DB on first
 *   launch, [io.nekohasekai.sagernet.database.DataStore.currentGroupId]) that
 *   cannot be expressed as `suspend` without restructuring the caller.
 * - On any **non-main thread**: executes [block] in place with no hop.
 *
 * Callers that already run on [Dispatchers.IO]/Default must not wrap again.
 * Debug builds optionally log a one-shot warning if a known Sager/Public DAO
 * is invoked directly from the main thread without this guard.
 */
fun <T> dbOffMain(block: () -> T): T =
    if (Looper.myLooper() == Looper.getMainLooper()) {
        runBlocking(Dispatchers.IO) { block() }
    } else {
        block()
    }
