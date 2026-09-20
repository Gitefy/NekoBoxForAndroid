package io.nekohasekai.sagernet.widget

/**
 * Decides whether an async Preference load may write back.
 * [runOnIoDispatcher] / [runOnMainDispatcher] are not parent/child jobs, so
 * cancelling the IO work does not cancel an already-posted main callback.
 * Callers bump [currentGeneration] on detach and on each new request.
 */
object PreferenceAsyncGuard {
    fun shouldApply(
        attached: Boolean,
        startedGeneration: Int,
        currentGeneration: Int,
    ): Boolean = attached && startedGeneration == currentGeneration

    fun shouldApplySummary(
        attached: Boolean,
        startedGeneration: Int,
        currentGeneration: Int,
        requestedCacheKey: String,
        liveCacheKey: String,
    ): Boolean = shouldApply(attached, startedGeneration, currentGeneration) &&
        requestedCacheKey == liveCacheKey
}
