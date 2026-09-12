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
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.coroutineContext

object RestoreCoordinator {
    enum class Phase { IDLE, PREPARED, CONFIG_COMMITTED, SAGER_COMMITTED, COMPLETE, INVALID }
    enum class LockKind { BOOT_RECOVERY, APPLY, USER_RESTORE }

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

    internal data class InternalSagerSnapshot(
        val groups: List<ProxyGroup>,
        val profiles: List<ProxyEntity>,
        val routers: List<RouterGroup>,
        val members: List<RouterMember>,
        val sources: List<RouterGroupSource>,
        val rules: List<RuleEntity>,
    )

    private data class RollbackResult(
        val restored: Boolean,
        val cleaned: Boolean,
    )

    class Permit internal constructor(
        private val dir: File,
        val kind: LockKind,
        private val raf: RandomAccessFile,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            runCatching { clearHolderMeta(dir) }
            runCatching { lock.release() }
            runCatching { raf.close() }
            if (kind == LockKind.USER_RESTORE) active.set(false)
        }
    }

    fun journalFile(dir: File) = File(dir, "restore-journal.phase")
    fun snapshotFile(dir: File) = File(dir, "restore-journal.prev-config")
    fun sagerSnapshotFile(dir: File) = File(dir, "restore-journal.prev-sager")
    fun lockFile(dir: File) = File(dir, "restore-apply.lock")
    fun lockMetaFile(dir: File) = File(dir, "restore-apply.lock.meta")

    fun readPhase(dir: File): Phase {
        val journal = journalFile(dir)
        if (!journal.exists()) return Phase.IDLE
        if (!journal.isFile) return Phase.INVALID
        val raw = runCatching { journal.readText().trim() }.getOrNull() ?: return Phase.INVALID
        return Phase.entries.firstOrNull { it != Phase.INVALID && it.name == raw } ?: Phase.INVALID
    }

    private fun writePhase(dir: File, phase: Phase) {
        journalFile(dir).writeText(phase.name)
    }

    fun tryExclusivePermit(dir: File, kind: LockKind = LockKind.APPLY): Permit? {
        dir.mkdirs()
        val file = lockFile(dir)
        if (!file.exists()) file.createNewFile()
        val pid = diagnosticPid()
        val process = currentProcessName()
        val phase = readPhase(dir).name
        trace(
            LockEvent("try", kind.name, acquired = false, phase = phase, pid = pid, process = process),
        )
        val raf = RandomAccessFile(file, "rw")
        val lock = try {
            raf.channel.tryLock()
        } catch (_: Throwable) {
            raf.close()
            trace(
                LockEvent("rejected", kind.name, acquired = false, phase = phase, pid = pid, process = process),
            )
            return null
        }
        if (lock == null) {
            raf.close()
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
            return null
        }
        writeHolderMeta(dir, kind, pid, process)
        if (kind == LockKind.USER_RESTORE) active.set(true)
        trace(
            LockEvent("acquired", kind.name, acquired = true, phase = phase, pid = pid, process = process),
        )
        return Permit(dir, kind, raf, lock)
    }

    suspend fun acquirePermit(
        dir: File,
        kind: LockKind,
        cancelled: () -> Boolean,
    ): AcquireOutcome = withContext(Dispatchers.IO) {
        val pid = diagnosticPid()
        val process = currentProcessName()
        while (coroutineContext.isActive && !cancelled()) {
            val permit = tryExclusivePermit(dir, kind)
            if (permit != null) return@withContext AcquireOutcome(permit, null)
            val holder = readHolderKind(dir)
            val phase = readPhase(dir).name
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
            runInterruptible { LockSupport.parkNanos(20_000_000L) }
        }
        AcquireOutcome(null, null)
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

    fun encodeRows(rows: List<KeyValuePair>): ByteArray {
        val out = StringBuilder()
        for (row in rows) {
            val payload = java.util.Base64.getEncoder().encodeToString(row.value)
            out.append(row.key).append('\u0001').append(row.valueType).append('\u0001').append(payload).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeRows(bytes: ByteArray): List<KeyValuePair> {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return emptyList()
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
        return installSagerExport(bytes) { snapshot ->
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.routerGroupSourceDao.reset()
                SagerDatabase.routerMemberDao.reset()
                SagerDatabase.routerGroupDao.reset()
                SagerDatabase.proxyDao.reset()
                SagerDatabase.groupDao.reset()
                SagerDatabase.rulesDao.reset()
                if (snapshot.groups.isNotEmpty()) SagerDatabase.groupDao.insert(snapshot.groups)
                if (snapshot.profiles.isNotEmpty()) SagerDatabase.proxyDao.insert(snapshot.profiles)
                if (snapshot.routers.isNotEmpty()) SagerDatabase.routerGroupDao.insert(snapshot.routers)
                if (snapshot.members.isNotEmpty()) SagerDatabase.routerMemberDao.insert(snapshot.members)
                if (snapshot.sources.isNotEmpty()) SagerDatabase.routerGroupSourceDao.insert(snapshot.sources)
                if (snapshot.rules.isNotEmpty()) SagerDatabase.rulesDao.insert(snapshot.rules)
            }
            true
        }
    }

    internal fun installSagerExport(
        bytes: ByteArray,
        install: (InternalSagerSnapshot) -> Boolean,
    ): Boolean {
        val snapshot = runCatching { decodeInternalSagerSnapshot(bytes) }.getOrNull() ?: return false
        return runCatching { install(snapshot) }.getOrDefault(false)
    }

    private fun decodeInternalSagerSnapshot(bytes: ByteArray): InternalSagerSnapshot {
        require(bytes.isNotEmpty()) { "empty internal Sager snapshot" }
        val text = String(bytes, Charsets.UTF_8)
        val root = JsonParser.parseString(text)
        require(root.isJsonObject) { "internal Sager snapshot must be a JSON object" }
        val structure = root.asJsonObject
        val version = structure.get("version")
        require(version?.isJsonPrimitive == true && version.asJsonPrimitive.isNumber &&
            version.asInt == BackupSerializer.BACKUP_VERSION) {
            "invalid internal Sager snapshot version"
        }
        val requiredArrays = listOf(
            "profiles",
            "groups",
            "routerGroups",
            "routerMembers",
            "routerSources",
            "rules",
            "routerRuleRefs",
        )
        requiredArrays.forEach { section ->
            require(structure.get(section)?.isJsonArray == true) {
                "missing or invalid internal Sager snapshot section: $section"
            }
        }
        if (requiredArrays.all { structure.getAsJsonArray(it).size() == 0 }) {
            return InternalSagerSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val content = JSONObject(text)
        val groups = BackupSerializer.getParcelableArray(content, "groups", ProxyGroup.CREATOR)
        val profiles = BackupSerializer.getParcelableArray(content, "profiles", ProxyEntity.CREATOR)
        val routers = BackupSerializer.getParcelableArray(content, "routerGroups", RouterGroup.CREATOR)
        val members = BackupSerializer.getParcelableArray(content, "routerMembers", RouterMember.CREATOR)
        val sources = BackupSerializer.getParcelableArray(content, "routerSources", RouterGroupSource.CREATOR)
        val rules = BackupSerializer.getParcelableArray(content, "rules") { parcel ->
            ParcelizeBridge.createRule(parcel)
        }
        BackupSerializer.getRouterRuleReferenceList(content)
        return InternalSagerSnapshot(groups, profiles, routers, members, sources, rules)
    }

    fun commit(
        dir: File,
        incomingConfig: List<KeyValuePair>?,
        capturePreviousConfig: () -> List<KeyValuePair>,
        capturePreviousSager: () -> ByteArray,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerIncoming: () -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
        deleteFile: (File) -> Boolean = { it.delete() },
    ): Outcome {
        val acquired = acquirePermitBlocking(dir, LockKind.USER_RESTORE)
        val permit = acquired.permit ?: return Outcome(false, readPhase(dir), acquired.error ?: ApplyErrorCodes.RESTORE_IN_PROGRESS)
        permit.use {
            val recovery = recoverLocked(dir, restoreConfig, restoreSagerPrevious, deleteFile)
            if (!recovery.success) return recovery
            val previousConfig = capturePreviousConfig()
            val previousSager = capturePreviousSager()
            snapshotFile(dir).writeBytes(encodeRows(previousConfig))
            sagerSnapshotFile(dir).writeBytes(previousSager)
            writePhase(dir, Phase.PREPARED)
            if (incomingConfig != null) {
                if (!restoreConfig(incomingConfig)) {
                    val rollback = rollbackBoth(dir, restoreConfig, restoreSagerPrevious, deleteFile)
                    return Outcome(
                        false,
                        if (rollback.cleaned) Phase.PREPARED else readPhase(dir),
                        if (rollback.restored && rollback.cleaned) "CONFIG_RESTORE_FAILED" else ApplyErrorCodes.RESTORE_FAILED,
                    )
                }
                writePhase(dir, Phase.CONFIG_COMMITTED)
            }
            if (!restoreSagerIncoming()) {
                val failedPhase = readPhase(dir)
                val rollback = rollbackBoth(dir, restoreConfig, restoreSagerPrevious, deleteFile)
                return Outcome(
                    false,
                    if (rollback.cleaned) failedPhase else readPhase(dir),
                    if (rollback.restored && rollback.cleaned) "SAGER_RESTORE_FAILED" else ApplyErrorCodes.RESTORE_FAILED,
                )
            }
            writePhase(dir, Phase.SAGER_COMMITTED)
            writePhase(dir, Phase.COMPLETE)
            return if (cleanup(dir, deleteFile)) {
                Outcome(true, Phase.COMPLETE, null)
            } else {
                Outcome(false, Phase.COMPLETE, ApplyErrorCodes.RESTORE_FAILED)
            }
        }
    }

    private fun acquirePermitBlocking(dir: File, kind: LockKind): AcquireOutcome {
        while (true) {
            val permit = tryExclusivePermit(dir, kind)
            if (permit != null) return AcquireOutcome(permit, null)
            val holder = readHolderKind(dir)
            if (failsAsUserRestore(kind, holder)) {
                return AcquireOutcome(null, ApplyErrorCodes.RESTORE_IN_PROGRESS)
            }
            LockSupport.parkNanos(20_000_000L)
        }
    }

    suspend fun recoverOnBoot(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Outcome {
        val pid = diagnosticPid()
        val process = currentProcessName()
        trace(
            LockEvent("recoverOnBoot-begin", LockKind.BOOT_RECOVERY.name, false, readPhase(dir).name, pid, process),
        )
        val acquired = acquirePermit(dir, LockKind.BOOT_RECOVERY) { false }
        val permit = acquired.permit ?: return Outcome(false, readPhase(dir), acquired.error ?: ApplyErrorCodes.RESTORE_IN_PROGRESS)
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
        deleteFile: (File) -> Boolean = { it.delete() },
    ): Outcome {
        val pid = diagnosticPid()
        val process = currentProcessName()
        trace(
            LockEvent("recovery-begin", "held", true, readPhase(dir).name, pid, process),
        )
        val phase = readPhase(dir)
        val out = when (phase) {
            Phase.INVALID -> Outcome(false, phase, ApplyErrorCodes.RESTORE_FAILED)
            Phase.IDLE -> {
                if (journalFile(dir).exists() || snapshotFile(dir).exists() || sagerSnapshotFile(dir).exists()) {
                    Outcome(false, phase, ApplyErrorCodes.RESTORE_FAILED)
                } else {
                    Outcome(true, Phase.IDLE, null)
                }
            }
            Phase.COMPLETE -> {
                if (cleanup(dir, deleteFile)) {
                    Outcome(true, Phase.IDLE, null)
                } else {
                    Outcome(false, Phase.COMPLETE, ApplyErrorCodes.RESTORE_FAILED)
                }
            }
            Phase.PREPARED, Phase.CONFIG_COMMITTED -> {
                val rollback = rollbackBoth(dir, restoreConfig, restoreSagerPrevious, deleteFile)
                if (rollback.restored && rollback.cleaned) {
                    Outcome(true, Phase.IDLE, "rolled back incomplete $phase")
                } else {
                    Outcome(false, if (rollback.restored) readPhase(dir) else phase, ApplyErrorCodes.RESTORE_FAILED)
                }
            }
            Phase.SAGER_COMMITTED -> {
                val markedComplete = runCatching { writePhase(dir, Phase.COMPLETE) }.isSuccess
                if (markedComplete && cleanup(dir, deleteFile)) {
                    Outcome(true, Phase.COMPLETE, null)
                } else {
                    Outcome(false, readPhase(dir), ApplyErrorCodes.RESTORE_FAILED)
                }
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
        deleteFile: (File) -> Boolean,
    ): RollbackResult {
        val configSnapshot = snapshotFile(dir)
        val sagerSnapshot = sagerSnapshotFile(dir)
        if (!configSnapshot.isFile || !sagerSnapshot.isFile) return RollbackResult(false, false)
        val previousConfig = runCatching {
            decodeRows(configSnapshot.readBytes())
        }.getOrNull() ?: return RollbackResult(false, false)
        val previousSager = runCatching {
            sagerSnapshot.readBytes()
        }.getOrNull() ?: return RollbackResult(false, false)
        val configRestored = runCatching { restoreConfig(previousConfig) }.getOrDefault(false)
        val sagerRestored = runCatching { restoreSagerPrevious(previousSager) }.getOrDefault(false)
        val restored = configRestored && sagerRestored
        if (!restored) return RollbackResult(false, false)
        if (runCatching { writePhase(dir, Phase.COMPLETE) }.isFailure) {
            return RollbackResult(true, false)
        }
        return RollbackResult(true, cleanup(dir, deleteFile))
    }

    private fun cleanup(dir: File, deleteFile: (File) -> Boolean): Boolean {
        for (file in listOf(snapshotFile(dir), sagerSnapshotFile(dir), journalFile(dir))) {
            if (!file.exists()) continue
            val deleted = runCatching { deleteFile(file) }.getOrDefault(false)
            if (!deleted || file.exists()) return false
        }
        return true
    }
}
