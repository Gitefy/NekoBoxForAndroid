package io.nekohasekai.sagernet.utils

object NativeGcPolicy {
    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val COOLDOWN_MS = 60_000L

    fun shouldForceNativeGc(
        level: Int,
        nowElapsed: Long,
        lastElapsed: Long,
        cooldownMs: Long = COOLDOWN_MS,
    ): Boolean {
        if (level == TRIM_MEMORY_UI_HIDDEN) return false
        if (nowElapsed - lastElapsed < cooldownMs) return false
        return true
    }
}
