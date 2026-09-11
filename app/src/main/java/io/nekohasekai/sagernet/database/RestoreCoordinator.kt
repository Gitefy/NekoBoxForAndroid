package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.bg.ApplyErrorCodes
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object RestoreCoordinator {
    enum class Phase { IDLE, PREPARED, CONFIG_COMMITTED, SAGER_COMMITTED, COMPLETE }

    private val active = AtomicBoolean(false)
    fun isActive(): Boolean = active.get()

    fun restoreInProgressError(): String = ApplyErrorCodes.RESTORE_IN_PROGRESS

    data class Outcome(val success: Boolean, val phase: Phase, val error: String? = null)

    fun journalFile(dir: File) = File(dir, "restore-journal.phase")
    fun snapshotFile(dir: File) = File(dir, "restore-journal.prev-config")

    fun readPhase(dir: File): Phase {
        val raw = journalFile(dir).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return Phase.entries.firstOrNull { it.name == raw } ?: Phase.IDLE
    }

    private fun writePhase(dir: File, phase: Phase) {
        journalFile(dir).writeText(phase.name)
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

    fun commit(
        dir: File,
        configRows: List<KeyValuePair>?,
        snapshotConfig: () -> List<KeyValuePair>,
        restoreConfig: (List<KeyValuePair>) -> Boolean,
        restoreSager: () -> Boolean,
    ): Outcome {
        if (!active.compareAndSet(false, true)) {
            return Outcome(false, readPhase(dir), ApplyErrorCodes.RESTORE_IN_PROGRESS)
        }
        try {
            writePhase(dir, Phase.PREPARED)
            if (configRows != null) {
                snapshotFile(dir).writeBytes(encodeRows(snapshotConfig()))
                if (!restoreConfig(configRows)) {
                    writePhase(dir, Phase.IDLE)
                    journalFile(dir).delete()
                    snapshotFile(dir).delete()
                    return Outcome(false, Phase.PREPARED, "CONFIG_RESTORE_FAILED")
                }
                writePhase(dir, Phase.CONFIG_COMMITTED)
            }
            if (!restoreSager()) {
                if (configRows != null && snapshotFile(dir).isFile) {
                    restoreConfig(decodeRows(snapshotFile(dir).readBytes()))
                }
                writePhase(dir, Phase.IDLE)
                journalFile(dir).delete()
                snapshotFile(dir).delete()
                return Outcome(false, Phase.CONFIG_COMMITTED, "SAGER_RESTORE_FAILED")
            }
            writePhase(dir, Phase.SAGER_COMMITTED)
            writePhase(dir, Phase.COMPLETE)
            journalFile(dir).delete()
            snapshotFile(dir).delete()
            return Outcome(true, Phase.COMPLETE, null)
        } finally {
            active.set(false)
        }
    }

    fun recoverOnBoot(dir: File, store: RoomPreferenceDataStore): Outcome {
        val phase = readPhase(dir)
        return when (phase) {
            Phase.IDLE, Phase.COMPLETE -> {
                journalFile(dir).delete()
                snapshotFile(dir).delete()
                Outcome(true, Phase.IDLE, null)
            }
            Phase.PREPARED -> {
                journalFile(dir).delete()
                snapshotFile(dir).delete()
                Outcome(true, Phase.IDLE, "abandoned PREPARED restore")
            }
            Phase.CONFIG_COMMITTED -> {
                if (snapshotFile(dir).isFile) {
                    val rows = decodeRows(snapshotFile(dir).readBytes())
                    store.restore(rows)
                }
                journalFile(dir).delete()
                snapshotFile(dir).delete()
                Outcome(true, Phase.IDLE, "rolled back incomplete CONFIG_COMMITTED")
            }
            Phase.SAGER_COMMITTED -> {
                journalFile(dir).delete()
                snapshotFile(dir).delete()
                Outcome(true, Phase.COMPLETE, null)
            }
        }
    }
}
