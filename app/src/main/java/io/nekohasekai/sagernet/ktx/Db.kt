package io.nekohasekai.sagernet.ktx

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Runs a blocking Room statement. On non-main threads the block executes
 * in place; on the main thread it is moved onto Dispatchers.IO and the
 * caller waits briefly. This is the last-resort guard for one-shot
 * bootstrap reads (empty database on first launch) that cannot be
 * expressed as suspend calls; regular hot paths must dispatch properly.
 */
fun <T> dbOffMain(block: () -> T): T =
    if (Looper.myLooper() == Looper.getMainLooper()) {
        runBlocking(Dispatchers.IO) { block() }
    } else {
        block()
    }
