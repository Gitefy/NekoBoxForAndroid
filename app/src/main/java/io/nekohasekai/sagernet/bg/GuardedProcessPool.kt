package io.nekohasekai.sagernet.bg

import android.os.SystemClock
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun loggedCommand(cmd: List<String>): String {
    val sensitive = Regex("(?i)(?:^|[-_])(?:key|password|passwd|secret|token|credential|auth)(?:$|[-_])")
    var redactNext = false
    return Commandline.toString(cmd.map { arg ->
        when {
            redactNext -> {
                redactNext = false
                "<redacted>"
            }
            arg.startsWith("-") && arg.contains('=') &&
                sensitive.containsMatchIn(arg.substringBefore('=')) ->
                arg.substringBefore('=') + "=<redacted>"
            arg.startsWith("-") && sensitive.containsMatchIn(arg) -> {
                redactNext = true
                arg
            }
            else -> arg
        }
    })
}

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process
        private var restartReason: String? = null

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        fun requestRestart(executable: String, reason: String) {
            if (File(cmd.first()).name != executable || restartReason != null || !process.isAlive) return
            restartReason = reason
            process.destroy()
        }

        fun ensureStopped() {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    throw IOException("External process did not stop after forced termination")
                }
            }
        }

        @DelicateCoroutinesApi
        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            var running = true
            val cmdName = File(cmd.first()).nameWithoutExtension
            var fastExitCount = 0
            try {
                while (true) {
                    val stderrJob = launch(Dispatchers.IO) {
                        streamLogger(process.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    val stdoutJob = launch(Dispatchers.IO) {
                        streamLogger(process.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                    stderrJob.cancel()
                    stdoutJob.cancel()
                    running = false
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    if (restartReason != null) {
                        Logs.i { "$cmdName restarting after $restartReason" }
                        restartReason = null
                        fastExitCount = 0
                    } else if (elapsed < 1000) {
                        fastExitCount++
                        if (fastExitCount >= 3) {
                            throw IOException(
                                "$cmdName exits too fast (exit code: $exitCode, attempts: $fastExitCount)"
                            )
                        }
                        Logs.w({ "$cmdName exited quickly in ${elapsed}ms (exit code: $exitCode), retry attempt $fastExitCount/3..." })
                        delay(300)
                    } else {
                        fastExitCount = 0
                        when {
                            exitCode == 128 + OsConstants.SIGKILL -> Logs.w({ "$cmdName was killed" })
                            else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                        }
                    }
                    Logs.i({ "restart process: ${loggedCommand(cmd)} (last exit code: $exitCode)" })
                    start()
                    running = true
                    onRestartCallback?.invoke()
                }
            } catch (e: IOException) {
                Logs.w({ "error occurred. stop guard: ${loggedCommand(cmd)}" })
                currentCoroutineContext().ensureActive()
                onFatal(e)
            } finally {
                if (running) withContext(NonCancellable) {  // clean-up cannot be cancelled
                    process.destroy()                       // kill the process
                    withContext(Dispatchers.IO) {
                        if (!process.waitFor(1, TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                            process.waitFor(1, TimeUnit.SECONDS)
                        }
                    }
                }                                           // otherwise process already exited, nothing to be done
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    var processCount = 0
    private val guards = ArrayList<Guard>()

    @MainThread
    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        Logs.i({ "start process: ${loggedCommand(cmd)}" })
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            guards.add(this)
            launch { looper(onRestartCallback) }
        }
        processCount += 1
    }

    @MainThread
    fun restart(executable: String, reason: String = "device wake") {
        if (coroutineContext[Job]?.isActive != true) return
        guards.forEach { it.requestRestart(executable, reason) }
    }

    @MainThread
    fun close(scope: CoroutineScope, onClosed: () -> Unit = {}) {
        scope.launch {
            closeAndJoin()
            onClosed()
        }
    }

    suspend fun closeAndJoin() {
        val job = coroutineContext[Job] ?: error("Process pool has no job")
        job.cancel()
        val failure = runStopCleanup(
            *guards.map { guard -> suspend { guard.ensureStopped() } }.toTypedArray(),
            { withTimeout(10_000) { job.join() } },
        )
        failure?.let { throw it }
    }
}
