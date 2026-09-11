package io.nekohasekai.sagernet.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ensures Settings preference binding runs exactly once after the store is Ready,
 * regardless of whether the page entered as Ready, Loading→Ready, or Failed→retry→Ready.
 */
class SettingsPreferenceSetupGate {
    private val started = AtomicBoolean(false)
    private val configureCount = AtomicInteger(0)

    fun applyReadySequence(
        initGlobal: () -> Unit,
        configure: () -> Unit,
        enable: () -> Unit,
    ): Boolean {
        if (!started.compareAndSet(false, true)) return false
        initGlobal()
        configure()
        configureCount.incrementAndGet()
        enable()
        return true
    }

    fun configureInvocations(): Int = configureCount.get()
    fun hasConfigured(): Boolean = configureCount.get() > 0
}
