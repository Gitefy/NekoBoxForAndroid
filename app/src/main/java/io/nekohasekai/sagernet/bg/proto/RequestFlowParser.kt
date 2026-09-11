package io.nekohasekai.sagernet.bg.proto

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.nekohasekai.sagernet.aidl.RequestFlowData

object RequestFlowParser {
    private val gson = Gson()

    data class Envelope(val flows: List<RawFlow>? = null)
    data class RawFlow(
        val id: String? = null,
        val createdAt: Long = 0L,
        val closedAt: Long = 0L,
        val closed: Boolean = false,
        val network: String? = null,
        val uid: Int = 0,
        val packageNames: String? = null,
        val domain: String? = null,
        val destinationAddress: String? = null,
        val destinationPort: Int = 0,
        val originDestination: String? = null,
        val matchedRuleText: String? = null,
        val chain: String? = null,
        val logicalOutbound: String? = null,
        val finalOutboundTag: String? = null,
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
    )

    fun parseSnapshot(json: String): List<RequestFlowData> {
        if (json.isBlank()) return emptyList()
        val envelope = try {
            gson.fromJson(json, Envelope::class.java)
        } catch (_: JsonSyntaxException) {
            return emptyList()
        } ?: return emptyList()
        return envelope.flows.orEmpty().map { row ->
            RequestFlowData(
                id = row.id.orEmpty(),
                createdAt = row.createdAt,
                closedAt = row.closedAt,
                closed = row.closed,
                network = row.network.orEmpty(),
                uid = row.uid,
                packageName = row.packageNames.orEmpty(),
                domain = row.domain.orEmpty(),
                destinationAddress = row.destinationAddress.orEmpty(),
                destinationPort = row.destinationPort,
                originDestination = row.originDestination.orEmpty(),
                matchedRuleText = row.matchedRuleText.orEmpty(),
                chain = row.chain.orEmpty(),
                logicalOutbound = row.logicalOutbound.orEmpty(),
                finalOutboundTag = row.finalOutboundTag.orEmpty(),
                uploadBytes = row.uploadBytes,
                downloadBytes = row.downloadBytes,
            )
        }
    }
}
