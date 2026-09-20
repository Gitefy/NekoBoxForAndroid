package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowData

/**
 * Deduplicates Request snapshot work without changing what gets published.
 * Raw JSON equality skips parse/map; structured fingerprints skip publish
 * when only unused JSON fields changed.
 */
object RequestSnapshotDedup {
    data class Fingerprint(
        val id: String,
        val createdAt: Long,
        val uploadBytes: Long,
        val downloadBytes: Long,
        val closed: Boolean,
        val logicalOutbound: String,
        val finalOutboundTag: String,
    ) {
        companion object {
            fun of(flow: RequestFlowData) = Fingerprint(
                id = flow.id,
                createdAt = flow.createdAt,
                uploadBytes = flow.uploadBytes,
                downloadBytes = flow.downloadBytes,
                closed = flow.closed,
                logicalOutbound = flow.logicalOutbound,
                finalOutboundTag = flow.finalOutboundTag,
            )

            fun listOf(flows: List<RequestFlowData>): List<Fingerprint> = flows.map(::of)
        }
    }

    fun shouldSkipUnparsed(lastRaw: String?, raw: String): Boolean =
        lastRaw != null && lastRaw == raw

    fun shouldSkipPublish(last: List<Fingerprint>?, next: List<Fingerprint>): Boolean =
        last != null && last == next

    /** Same fields as the previous joinToString fingerprint, for equivalence tests. */
    fun legacyString(flows: List<RequestFlowData>): String =
        flows.joinToString(separator = "|") { flow ->
            "${flow.id}:${flow.createdAt}:${flow.uploadBytes}:${flow.downloadBytes}:${flow.closed}:${flow.logicalOutbound}:${flow.finalOutboundTag}"
        }
}
