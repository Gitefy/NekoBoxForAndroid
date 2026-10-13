package io.nekohasekai.sagernet.database

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.BackupSerializer
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.UUID
import java.io.IOException
import kotlin.coroutines.coroutineContext

object RestoreCoordinator {
    enum class Phase { IDLE, PREPARING, PREPARED, CONFIG_COMMITTED, SAGER_COMMITTED, COMPLETE }
    enum class LockKind { BOOT_RECOVERY, APPLY, USER_RESTORE, SUBSCRIPTION_WRITE }

    class SubscriptionWriteRejected(message: String) : IOException(message)

    data class LockEvent(
        val op: String,
        val kind: String,
        val acquired: Boolean,
        val phase: String,
        val pid: Int,
        val process: String,
        val error: String? = null,
        val holder: String? = null,
    )

    private val ROUTINE_LOCK_OPS = setOf(
        "try",
        "acquired",
        "recovery-begin",
        "recovery-end",
        "recoverOnBoot-begin",
        "recoverOnBoot-end",
    )

    data class AcquireOutcome(
        val permit: Permit?,
        val error: String?,
    )

    @Volatile
    var lockSink: ((LockEvent) -> Unit)? = null

    private val active = AtomicBoolean(false)
    fun isActive(): Boolean = active.get()
    fun restoreInProgressError(): String = ApplyErrorCodes.RESTORE_IN_PROGRESS

    data class Outcome(val success: Boolean, val phase: Phase, val error: String? = null)

    class Permit internal constructor(
        private val dir: File,
        val kind: LockKind,
        private val raf: RandomAccessFile,
        private val lock: FileLock,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (kind == LockKind.USER_RESTORE) active.set(false)
            runCatching { clearHolderMeta(dir) }
            runCatching { lock.release() }
            runCatching { raf.close() }
        }
    }

    fun journalFile(dir: File) = File(dir, "restore-journal.phase")
    fun snapshotFile(dir: File) = File(dir, "restore-journal.prev-config")
    fun sagerSnapshotFile(dir: File) = File(dir, "restore-journal.prev-sager")
    fun lockFile(dir: File) = File(dir, "restore-apply.lock")
    fun lockMetaFile(dir: File) = File(dir, "restore-apply.lock.meta")
    internal fun generationFile(dir: File) = File(dir, "restore-generation")

    suspend fun <T> withSubscriptionWrite(
        dir: File,
        expectedGeneration: String? = null,
        block: suspend (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val permit = tryExclusivePermit(dir, LockKind.SUBSCRIPTION_WRITE)
            ?: throw SubscriptionWriteRejected("Restore or another database operation is in progress")
        permit.use {
            if (readPhase(dir) != Phase.IDLE) {
                throw SubscriptionWriteRejected("Restore recovery must finish before subscription updates")
            }
            val generation = generationFile(dir).takeIf { it.isFile }?.readText().orEmpty()
            if (expectedGeneration != null && generation != expectedGeneration) {
                throw SubscriptionWriteRejected("Restore changed the database during subscription download")
            }
            block(generation)
        }
    }

    fun readPhase(dir: File): Phase? {
        val file = journalFile(dir)
        if (!file.isFile) {
            if (snapshotFile(dir).isFile || sagerSnapshotFile(dir).isFile) {
                return null
            }
            return Phase.IDLE
        }
        val raw = file.readText().trim()
        if (raw.isEmpty()) return null
        return Phase.entries.firstOrNull { it.name == raw }
    }

    private fun syncDirectory(dir: File) {
        // Windows JVM tests cannot open directories as FileChannels; Android can.
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun writeDurable(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }

    private fun writePhase(dir: File, phase: Phase) {
        val target = journalFile(dir)
        val tmp = File(dir, "restore-journal.phase.tmp")
        writeDurable(tmp, phase.name.toByteArray(Charsets.UTF_8))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(dir)
    }

    fun tryExclusivePermit(
        dir: File,
        kind: LockKind = LockKind.APPLY,
        traceOnFailure: Boolean = true,
    ): Permit? {
        dir.mkdirs()
        val file = lockFile(dir)
        if (!file.exists()) file.createNewFile()
        val pid = diagnosticPid()
        val process = currentProcessName()
        val phase = readPhase(dir)?.name ?: "CORRUPT"
        if (traceOnFailure) {
            trace(
                LockEvent("try", kind.name, acquired = false, phase = phase, pid = pid, process = process),
            )
        }
        val raf = RandomAccessFile(file, "rw")
        val lock = try {
            raf.channel.tryLock()
        } catch (_: Throwable) {
            raf.close()
            if (traceOnFailure) {
                trace(
                    LockEvent("rejected", kind.name, acquired = false, phase = phase, pid = pid, process = process),
                )
            }
            return null
        }
        if (lock == null) {
            raf.close()
            if (traceOnFailure) {
                trace(
                    LockEvent(
                        "rejected",
                        kind.name,
                        acquired = false,
                        phase = phase,
                        pid = pid,
                        process = process,
                        holder = readHolderKind(dir)?.name,
                    ),
                )
            }
            return null
        }
        val permit = Permit(dir, kind, raf, lock)
        try {
            writeHolderMeta(dir, kind, pid, process)
            if (kind == LockKind.USER_RESTORE) {
                active.set(true)
                // Fence downloads started before this restore, even if the restore rolls back.
                val tmp = File(dir, "restore-generation.tmp")
                writeDurable(tmp, UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
                Files.move(tmp.toPath(), generationFile(dir).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(dir)
            }
            trace(
                LockEvent("acquired", kind.name, acquired = true, phase = phase, pid = pid, process = process),
            )
            return permit
        } catch (error: Throwable) {
            permit.close()
            throw error
        }
    }

    suspend fun acquirePermit(
        dir: File,
        kind: LockKind,
        cancelled: () -> Boolean,
    ): AcquireOutcome {
        var acquired: Permit? = null
        try {
            return withContext(Dispatchers.IO) {
                val pid = diagnosticPid()
                val process = currentProcessName()
                var hasWaited = false
                while (coroutineContext.isActive && !cancelled()) {
                    val permit = tryExclusivePermit(dir, kind, traceOnFailure = !hasWaited)
                    if (permit != null) {
                        acquired = permit
                        return@withContext AcquireOutcome(permit, null)
                    }
                    val holder = readHolderKind(dir)
                    val phase = readPhase(dir)?.name ?: "CORRUPT"
                    if (failsAsUserRestore(kind, holder)) {
                        trace(
                            LockEvent(
                                "restore-in-progress",
                                kind.name,
                                acquired = false,
                                phase = phase,
                                pid = pid,
                                process = process,
                                error = ApplyErrorCodes.RESTORE_IN_PROGRESS,
                                holder = holder?.name,
                            ),
                        )
                        return@withContext AcquireOutcome(null, ApplyErrorCodes.RESTORE_IN_PROGRESS)
                    }
                    if (!hasWaited) {
                        hasWaited = true
                        trace(
                            LockEvent(
                                "wait",
                                kind.name,
                                acquired = false,
                                phase = phase,
                                pid = pid,
                                process = process,
                                holder = holder?.name,
                            ),
                        )
                    }
                    runInterruptible { LockSupport.parkNanos(20_000_000L) }
                }
                AcquireOutcome(null, null)
            }
        } catch (error: Throwable) {
            acquired?.close()
            throw error
        }
    }

    fun readHolderKind(dir: File): LockKind? {
        val raw = lockMetaFile(dir).takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return LockKind.entries.firstOrNull { it.name == raw }
    }

    private fun failsAsUserRestore(kind: LockKind, holder: LockKind?): Boolean {
        if (holder != LockKind.USER_RESTORE) return false
        return kind != LockKind.USER_RESTORE
    }

    private fun writeHolderMeta(dir: File, kind: LockKind, pid: Int, process: String) {
        lockMetaFile(dir).writeText("${kind.name}\n$pid\n$process")
    }

    private fun clearHolderMeta(dir: File) {
        lockMetaFile(dir).delete()
    }

    private fun diagnosticPid(): Int = runCatching { android.os.Process.myPid() }.getOrDefault(0)

    private fun currentProcessName(): String = runCatching {
        moe.matsuri.nb4a.utils.JavaUtil.getProcessName()
    }.getOrElse { "pid-${diagnosticPid()}" }

    internal fun lockTraceIsRoutine(event: LockEvent): Boolean {
        if (!event.error.isNullOrBlank()) return false
        return event.op in ROUTINE_LOCK_OPS
    }

    private fun trace(event: LockEvent) {
        val line = {
            "restore-lock op=${event.op} kind=${event.kind} acquired=${event.acquired} " +
                "phase=${event.phase} pid=${event.pid} process=${event.process} " +
                "holder=${event.holder ?: "-"} error=${event.error ?: "-"}"
        }
        if (lockTraceIsRoutine(event)) Logs.d(line) else Logs.w(line)
        lockSink?.invoke(event)
    }

    private const val SNAP_MAGIC = "#EGOX_SNAP_V1\n"

    fun encodeRows(rows: List<KeyValuePair>): ByteArray {
        val out = StringBuilder(SNAP_MAGIC)
        for (row in rows) {
            val payload = java.util.Base64.getEncoder().encodeToString(row.value)
            out.append(row.key).append('\u0001').append(row.valueType).append('\u0001').append(payload).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeRows(bytes: ByteArray): List<KeyValuePair> {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.startsWith(SNAP_MAGIC)) {
            val content = text.substring(SNAP_MAGIC.length)
            if (content.isBlank()) return emptyList()
            return content.lineSequence().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\u0001')
                require(parts.size == 3) { "malformed restore snapshot" }
                KeyValuePair(parts[0]).apply {
                    valueType = parts[1].toInt()
                    value = java.util.Base64.getDecoder().decode(parts[2])
                }
            }.toList()
        }
        if (text.isBlank()) throw IllegalArgumentException("empty snapshot")
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\u0001')
            require(parts.size == 3) { "malformed restore snapshot" }
            KeyValuePair(parts[0]).apply {
                valueType = parts[1].toInt()
                value = java.util.Base64.getDecoder().decode(parts[2])
            }
        }.toList()
    }

    fun captureSagerExportBytes(): ByteArray =
        BackupSerializer.exportDatabase(SagerDatabase.instance, true, true).toString().toByteArray(Charsets.UTF_8)

    fun installSagerExport(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            val content = JSONObject(String(bytes, Charsets.UTF_8))
            val groups = if (content.has("groups")) BackupSerializer.getParcelableArray(content, "groups", ProxyGroup.CREATOR) else emptyList()
            val profiles = if (content.has("profiles")) BackupSerializer.getParcelableArray(content, "profiles", ProxyEntity.CREATOR) else emptyList()
            val routers = if (content.has("routerGroups")) BackupSerializer.getParcelableArray(content, "routerGroups", RouterGroup.CREATOR) else emptyList()
            val members = if (content.has("routerMembers")) BackupSerializer.getParcelableArray(content, "routerMembers", RouterMember.CREATOR) else emptyList()
            val sources = if (content.has("routerSources")) BackupSerializer.getParcelableArray(content, "routerSources", RouterGroupSource.CREATOR) else emptyList()
            val rules = if (content.has("rules")) {
                BackupSerializer.getParcelableArray(content, "rules") { parcel ->
                    ParcelizeBridge.createRule(parcel)
                }
            } else emptyList<RuleEntity>()
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.routerGroupSourceDao.reset()
                SagerDatabase.routerMemberDao.reset()
                SagerDatabase.routerGroupDao.reset()
                SagerDatabase.proxyDao.reset()
                SagerDatabase.groupDao.reset()
                SagerDatabase.rulesDao.reset()
                if (groups.isNotEmpty()) SagerDatabase.groupDao.insert(groups)
                if (profiles.isNotEmpty()) SagerDatabase.proxyDao.insert(profiles)
                if (routers.isNotEmpty()) SagerDatabase.routerGroupDao.insert(routers)
                if (members.isNotEmpty()) SagerDatabase.routerMemberDao.insert(members)
                if (sources.isNotEmpty()) SagerDatabase.routerGroupSourceDao.insert(sources)
                if (rules.isNotEmpty()) SagerDatabase.rulesDao.insert(rules)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun commit(
        dir: File,
        incomingConfig: List<KeyValuePair>?,
        capturePreviousConfig: () -> List<KeyValuePair>,
        capturePreviousSager: () -> ByteArray,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerIncoming: () -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Outcome {
        val acquired = acquirePermitBlocking(dir, LockKind.USER_RESTORE)
        val permit = acquired.permit ?: return Outcome(false, readPhase(dir) ?: Phase.IDLE, acquired.error ?: ApplyErrorCodes.RESTORE_IN_PROGRESS)
        permit.use {
            val previousPhase = readPhase(dir)
            if (previousPhase != Phase.IDLE) {
                return Outcome(false, previousPhase ?: Phase.IDLE, ApplyErrorCodes.RESTORE_FAILED)
            }
            val previousConfig = capturePreviousConfig()
            val previousSager = capturePreviousSager()
            // Before PREPARED, live databases are untouched; interrupted preparation is disposable.
            writePhase(dir, Phase.PREPARING)
            writeDurable(snapshotFile(dir), encodeRows(previousConfig))
            writeDurable(sagerSnapshotFile(dir), previousSager)
            syncDirectory(dir)
            writePhase(dir, Phase.PREPARED)
            if (incomingConfig != null) {
                if (!restoreConfig(incomingConfig)) {
                    val rolledBack = rollbackBoth(dir, restoreConfig, restoreSagerPrevious)
                    return Outcome(false, Phase.PREPARED, if (rolledBack) "CONFIG_RESTORE_FAILED" else "CONFIG_RESTORE_AND_ROLLBACK_FAILED")
                }
                writePhase(dir, Phase.CONFIG_COMMITTED)
            }
            if (!restoreSagerIncoming()) {
                val rolledBack = rollbackBoth(dir, restoreConfig, restoreSagerPrevious)
                return Outcome(false, Phase.CONFIG_COMMITTED, if (rolledBack) "SAGER_RESTORE_FAILED" else "SAGER_RESTORE_AND_ROLLBACK_FAILED")
            }
            writePhase(dir, Phase.SAGER_COMMITTED)
            writePhase(dir, Phase.COMPLETE)
            val cleaned = cleanup(dir)
            return Outcome(cleaned, Phase.COMPLETE, if (cleaned) null else "restore cleanup failed")
        }
    }

    private fun acquirePermitBlocking(dir: File, kind: LockKind): AcquireOutcome {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val permit = tryExclusivePermit(dir, kind)
            if (permit != null) return AcquireOutcome(permit, null)
            val holder = readHolderKind(dir)
            if (failsAsUserRestore(kind, holder)) {
                return AcquireOutcome(null, ApplyErrorCodes.RESTORE_IN_PROGRESS)
            }
            if (Thread.currentThread().isInterrupted) {
                return AcquireOutcome(null, ApplyErrorCodes.TIMEOUT)
            }
            LockSupport.parkNanos(20_000_000L)
        }
        return AcquireOutcome(null, ApplyErrorCodes.TIMEOUT)
    }

    suspend fun recoverOnBoot(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Outcome {
        val initialPhase = readPhase(dir)
        if (initialPhase == Phase.IDLE) {
            return Outcome(true, Phase.IDLE, null)
        }
        val pid = diagnosticPid()
        val process = currentProcessName()
        trace(
            LockEvent("recoverOnBoot-begin", LockKind.BOOT_RECOVERY.name, false, initialPhase?.name ?: "CORRUPT", pid, process),
        )
        val acquired = acquirePermit(dir, LockKind.BOOT_RECOVERY) { false }
        val permit = acquired.permit ?: return Outcome(false, readPhase(dir) ?: Phase.IDLE, acquired.error ?: ApplyErrorCodes.RESTORE_IN_PROGRESS)
        return permit.use {
            val out = recoverLocked(dir, restoreConfig, restoreSagerPrevious)
            trace(
                LockEvent("recoverOnBoot-end", LockKind.BOOT_RECOVERY.name, true, out.phase.name, pid, process, error = out.error),
            )
            out
        }
    }

    fun recoverLocked(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Outcome {
        val pid = diagnosticPid()
        val process = currentProcessName()
        trace(
            LockEvent("recovery-begin", "held", true, readPhase(dir)?.name ?: "CORRUPT", pid, process),
        )
        val phase = readPhase(dir)
        if (phase == null) {
            val out = Outcome(false, Phase.IDLE, "corrupt journal file")
            trace(
                LockEvent("recovery-end", "held", true, out.phase.name, pid, process, error = out.error),
            )
            return out
        }
        val out = when (phase) {
            Phase.IDLE, Phase.PREPARING, Phase.COMPLETE -> {
                val cleaned = cleanup(dir)
                Outcome(cleaned, if (cleaned) Phase.IDLE else phase, if (cleaned) null else "restore cleanup failed")
            }
            Phase.PREPARED, Phase.CONFIG_COMMITTED -> {
                val ok = rollbackBoth(dir, restoreConfig, restoreSagerPrevious)
                if (ok) {
                    Outcome(true, Phase.IDLE, "rolled back incomplete $phase")
                } else {
                    Outcome(false, phase, "rollback failed")
                }
            }
            Phase.SAGER_COMMITTED -> {
                writePhase(dir, Phase.COMPLETE)
                val cleaned = cleanup(dir)
                Outcome(cleaned, Phase.COMPLETE, if (cleaned) null else "restore cleanup failed")
            }
        }
        trace(
            LockEvent("recovery-end", "held", true, out.phase.name, pid, process, error = out.error),
        )
        return out
    }

    private fun rollbackBoth(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Boolean {
        val configFile = snapshotFile(dir)
        val sagerFile = sagerSnapshotFile(dir)
        val rows = runCatching { decodeRows(configFile.readBytes()) }.getOrNull()
        val bytes = runCatching { sagerFile.readBytes() }.getOrNull()
        if (rows == null || bytes == null || !hasUsableSagerSnapshot(bytes)) {
            Logs.w("restore rollback requires both valid snapshots")
            return false
        }
        val configSuccess = runCatching { restoreConfig(rows) }.getOrDefault(false)
        val sagerSuccess = runCatching { restoreSagerPrevious(bytes) }.getOrDefault(false)
        val allOk = configSuccess && sagerSuccess
        if (!allOk) return false
        writePhase(dir, Phase.COMPLETE)
        return cleanup(dir)
    }

    internal fun hasUsableSagerSnapshot(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return runCatching {
            val root = JsonParser.parseString(String(bytes, Charsets.UTF_8))
            if (!root.isJsonObject) return@runCatching false
            val obj = root.asJsonObject
            val required = listOf("groups", "profiles", "rules")
            val optional = listOf("routerGroups", "routerMembers", "routerSources")
            required.all { obj.get(it)?.isJsonArray == true } &&
                optional.all { !obj.has(it) || obj.get(it).isJsonArray }
        }.getOrDefault(false)
    }

    private fun cleanup(dir: File): Boolean {
        // Keep the terminal/preparation marker until every snapshot deletion is durable.
        for (file in listOf(snapshotFile(dir), sagerSnapshotFile(dir), File(dir, "restore-journal.phase.tmp"))) {
            if (file.exists() && !file.delete()) return false
        }
        syncDirectory(dir)
        val journal = journalFile(dir)
        if (journal.exists() && !journal.delete()) return false
        syncDirectory(dir)
        return true
    }
}
