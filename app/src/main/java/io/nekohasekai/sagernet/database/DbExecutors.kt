package io.nekohasekai.sagernet.database

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared Room executors for the app databases. Room runs suspend/continuation
 * work on these pools; blocking DAO calls keep running on the caller's thread,
 * so with `allowMainThreadQueries()` removed every call site must guarantee a
 * non-main dispatcher. Named threads keep ANR traces attributable.
 *
 * Sizing (P01): query 4 threads, write/transaction 1 thread. Not scaled for
 * 200+ nodes; TempDatabase keeps main-thread queries (in-memory only).
 */
object DbExecutors {

    private val querySeq = AtomicInteger(1)

    fun pool(size: Int, name: String): ExecutorService =
        Executors.newFixedThreadPool(size) { runnable ->
            Thread(runnable, "$name-${querySeq.getAndIncrement()}").apply { isDaemon = true }
        }

    fun single(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    val query: ExecutorService by lazy { pool(4, "db-query") }
    val write: ExecutorService by lazy { single("db-write") }

    val sagerQuery: ExecutorService get() = query
    val sagerTransaction: ExecutorService get() = write
}
