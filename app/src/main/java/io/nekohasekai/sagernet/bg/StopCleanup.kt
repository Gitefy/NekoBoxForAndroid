package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Every resource must be attempted, including when STOP races with cancellation. */
internal suspend fun runStopCleanup(vararg steps: suspend () -> Unit): Throwable? =
    withContext(NonCancellable) {
        var failure: Throwable? = null
        for (step in steps) {
            try {
                step()
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure
    }
