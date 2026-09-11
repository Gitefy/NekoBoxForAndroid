package io.nekohasekai.sagernet.database

object RequestRuleApply {
    enum class Outcome { PERSIST_FAILED, RELOAD_FAILED, APPLIED }

    data class Result(val outcome: Outcome, val persisted: Boolean)

    suspend fun saveAndApply(
        persist: suspend () -> Boolean,
        reload: suspend () -> Boolean,
    ): Result {
        val ok = persist()
        if (!ok) return Result(Outcome.PERSIST_FAILED, persisted = false)
        return if (reload()) {
            Result(Outcome.APPLIED, persisted = true)
        } else {
            Result(Outcome.RELOAD_FAILED, persisted = true)
        }
    }
}
