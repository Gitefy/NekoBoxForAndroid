package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

/**
 * Central log gate for the Kotlin side.
 *
 * The user-facing "Log Level" setting (logLevel: 0 none / 1 warn / 2 info /
 * 3 debug / 4 trace) drives two things: the sing-box core log level (set in
 * ConfigBuilder) and whether Kotlin-side log lines reach libcore. Without the
 * gate every Logs.d call paid for string interpolation, a full
 * Thread.stackTrace capture (mkTag), and a JNI hop into nekoLogPrintln — even
 * when the user selected "none".
 *
 * Level source: [DataStore] registers a lightweight provider reading the
 * in-memory KvMemoryCache mirror (a read-lock + map lookup, no I/O), so the
 * gate sees writes from both processes as soon as the mirror merges them and
 * never needs its own cache or listener. [Logs] must not import [DataStore]
 * back: [RoomPreferenceDataStore] already depends on [Logs], so the edge may
 * only point DataStore -> Logs.
 *
 * Callers must use the lazy overloads (message: () -> String) whenever the
 * message involves interpolation, joins, or exception rendering; the lambda
 * only runs when the line will actually be emitted.
 */
object Logs {

    @Volatile
    private var levelProvider: (() -> Int)? = null

    /** Called once from DataStore init; cheap, idempotent. */
    fun setLevelProvider(provider: () -> Int) {
        levelProvider = provider
    }

    private fun level(): Int {
        return runCatching { levelProvider?.invoke() }.getOrNull()?.takeIf { it >= 0 }
            // Level unknown (very early startup, before DataStore is up):
            // keep warnings/errors, drop info/debug.
            ?: 1
    }

    fun isDebugEnabled(): Boolean = level() >= 3
    fun isInfoEnabled(): Boolean = level() >= 2

    private fun mkTag(): String {
        // Fixed indices break as soon as inline overloads change the frame depth,
        // so scan for the first frame outside this object instead.
        val self = Logs::class.java.name
        for (element in Thread.currentThread().stackTrace) {
            val name = element.className
            if (name == self || name == "java.lang.Thread") continue
            if (name.startsWith("dalvik.")) continue
            return name.substringAfterLast(".")
        }
        return "Logs"
    }

    // level int use logrus.go

    // NOTE: the lazy overloads are deliberately *not* inline. A public inline
    // function may not touch private helpers (level/mkTag), and the lambda
    // allocation itself is negligible next to the stack-trace capture + JNI
    // hop it guards.

    fun d(message: String) {
        if (!isDebugEnabled()) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message")
    }

    fun d(message: () -> String) {
        if (!isDebugEnabled()) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] ${message()}")
    }

    fun d(message: String, exception: Throwable) {
        if (!isDebugEnabled()) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun d(exception: Throwable, message: () -> String) {
        if (!isDebugEnabled()) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] ${message()}" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        if (!isInfoEnabled()) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message")
    }

    fun i(message: () -> String) {
        if (!isInfoEnabled()) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] ${message()}")
    }

    fun i(message: String, exception: Throwable) {
        if (!isInfoEnabled()) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        if (level() < 1) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message")
    }

    fun w(message: () -> String) {
        if (level() < 1) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] ${message()}")
    }

    fun w(message: String, exception: Throwable) {
        if (level() < 1) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable, message: () -> String) {
        if (level() < 1) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] ${message()}" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        if (level() < 1) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}
