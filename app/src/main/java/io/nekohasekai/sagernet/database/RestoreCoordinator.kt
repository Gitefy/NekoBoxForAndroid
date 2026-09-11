package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.BackupSerializer
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean

object RestoreCoordinator {
    enum class Phase { IDLE, PREPARED, CONFIG_COMMITTED, SAGER_COMMITTED, COMPLETE }

    private val active = AtomicBoolean(false)
    fun isActive(): Boolean = active.get()
    fun restoreInProgressError(): String = ApplyErrorCodes.RESTORE_IN_PROGRESS

    data class Outcome(val success: Boolean, val phase: Phase, val error: String? = null)

    class Permit internal constructor(
        private val raf: RandomAccessFile,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { raf.close() }
            active.set(false)
        }
    }

    fun journalFile(dir: File) = File(dir, "restore-journal.phase")
    fun snapshotFile(dir: File) = File(dir, "restore-journal.prev-config")
    fun sagerSnapshotFile(dir: File) = File(dir, "restore-journal.prev-sager")
    fun lockFile(dir: File) = File(dir, "restore-apply.lock")

    fun readPhase(dir: File): Phase {
        val raw = journalFile(dir).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return Phase.entries.firstOrNull { it.name == raw } ?: Phase.IDLE
    }

    private fun writePhase(dir: File, phase: Phase) {
        journalFile(dir).writeText(phase.name)
    }

    fun tryExclusivePermit(dir: File): Permit? {
        dir.mkdirs()
        val file = lockFile(dir)
        if (!file.exists()) file.createNewFile()
        val raf = RandomAccessFile(file, "rw")
        val lock = try {
            raf.channel.tryLock()
        } catch (_: Throwable) {
            raf.close()
            return null
        }
        if (lock == null) {
            raf.close()
            return null
        }
        active.set(true)
        return Permit(raf, lock)
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
        val permit = tryExclusivePermit(dir) ?: return Outcome(false, readPhase(dir), ApplyErrorCodes.RESTORE_IN_PROGRESS)
        permit.use {
            val previousConfig = capturePreviousConfig()
            val previousSager = capturePreviousSager()
            snapshotFile(dir).writeBytes(encodeRows(previousConfig))
            sagerSnapshotFile(dir).writeBytes(previousSager)
            writePhase(dir, Phase.PREPARED)
            if (incomingConfig != null) {
                if (!restoreConfig(incomingConfig)) {
                    rollbackBoth(dir, restoreConfig, restoreSagerPrevious)
                    return Outcome(false, Phase.PREPARED, "CONFIG_RESTORE_FAILED")
                }
                writePhase(dir, Phase.CONFIG_COMMITTED)
            }
            if (!restoreSagerIncoming()) {
                rollbackBoth(dir, restoreConfig, restoreSagerPrevious)
                return Outcome(false, Phase.CONFIG_COMMITTED, "SAGER_RESTORE_FAILED")
            }
            writePhase(dir, Phase.SAGER_COMMITTED)
            writePhase(dir, Phase.COMPLETE)
            cleanup(dir)
            return Outcome(true, Phase.COMPLETE, null)
        }
    }

    fun recoverOnBoot(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Outcome {
        val permit = tryExclusivePermit(dir) ?: return Outcome(false, readPhase(dir), ApplyErrorCodes.RESTORE_IN_PROGRESS)
        permit.use { return recoverLocked(dir, restoreConfig, restoreSagerPrevious) }
    }

    fun recoverLocked(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ): Outcome {
        val phase = readPhase(dir)
        return when (phase) {
            Phase.IDLE, Phase.COMPLETE -> {
                cleanup(dir)
                Outcome(true, Phase.IDLE, null)
            }
            Phase.PREPARED, Phase.CONFIG_COMMITTED -> {
                rollbackBoth(dir, restoreConfig, restoreSagerPrevious)
                Outcome(true, Phase.IDLE, "rolled back incomplete $phase")
            }
            Phase.SAGER_COMMITTED -> {
                cleanup(dir)
                Outcome(true, Phase.COMPLETE, null)
            }
        }
    }

    private fun rollbackBoth(
        dir: File,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSagerPrevious: (ByteArray) -> Boolean,
    ) {
        if (snapshotFile(dir).isFile) {
            restoreConfig(decodeRows(snapshotFile(dir).readBytes()))
        }
        if (sagerSnapshotFile(dir).isFile) {
            restoreSagerPrevious(sagerSnapshotFile(dir).readBytes())
        }
        cleanup(dir)
    }

    private fun cleanup(dir: File) {
        journalFile(dir).delete()
        snapshotFile(dir).delete()
        sagerSnapshotFile(dir).delete()
    }
}
