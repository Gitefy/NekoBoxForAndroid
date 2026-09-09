package io.nekohasekai.sagernet.database

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shared Room executors for the app databases. Room runs suspend/continuation
 * work on these pools; blocking DAO calls keep running on the caller's thread,
 * so with `allowMainThreadQueries()` removed every call site must guarantee a
 * non-main dispatcher. Named threads keep ANR traces attributable.
 */
object DbExecutors {

    fun pool(size: Int, name: String): ExecutorService =
        Executors.newFixedThreadPool(size) { runnable ->
            Thread(runnable, "$name-${runnable.hashCode()}").apply { isDaemon = true }
        }

    fun single(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    val sagerQuery: ExecutorService by lazy { pool(4, "sager-db-query") }
    val sagerTransaction: ExecutorService by lazy { single("sager-db-transaction") }
}
