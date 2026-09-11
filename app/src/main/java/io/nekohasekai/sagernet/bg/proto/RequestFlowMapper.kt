package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowData
import io.nekohasekai.sagernet.fmt.TAG_BLOCK
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_DIRECT

data class RequestDisplayMaps(
    val selectorToStable: Map<String, String> = emptyMap(),
    val stableToName: Map<String, String> = emptyMap(),
    val tagToProfileName: Map<String, String> = emptyMap(),
) {
    companion object {
        fun fromRuntime(
            routerSelectorTags: Map<String, String>,
            stableToName: Map<String, String>,
            tagToProfileName: Map<String, String>,
        ): RequestDisplayMaps {
            val selectorToStable = HashMap<String, String>(routerSelectorTags.size)
            routerSelectorTags.forEach { (stable, selector) ->
                if (selector.isNotBlank()) selectorToStable[selector] = stable
            }
            return RequestDisplayMaps(selectorToStable, stableToName, tagToProfileName)
        }
    }
}

object RequestFlowMapper {
    const val KIND_PROXY = "proxy"
    const val KIND_DIRECT = "direct"
    const val KIND_BLOCK = "block"

    fun map(flow: RequestFlowData, maps: RequestDisplayMaps): RequestFlowData {
        val logical = flow.logicalOutbound
        val finalTag = flow.finalOutboundTag
        val kind = kindOf(finalTag, logical)
        val stable = maps.selectorToStable[logical] ?: maps.selectorToStable[finalTag].orEmpty()
        val routerName = when {
            stable.isNotBlank() -> maps.stableToName[stable] ?: stable
            else -> ""
        }
        val finalName = when (kind) {
            KIND_DIRECT -> "DIRECT"
            KIND_BLOCK -> "REJECT"
            else -> maps.tagToProfileName[finalTag] ?: finalTag
        }
        return flow.copy(
            routerStableTag = stable,
            routerName = routerName,
            finalProfileName = finalName,
            kind = kind,
        )
    }

    fun kindOf(finalTag: String, logical: String = ""): String {
        val tags = listOf(finalTag, logical)
        if (tags.any { it == TAG_BLOCK || it.equals("block", true) || it.equals("reject", true) }) {
            return KIND_BLOCK
        }
        if (tags.any { it == TAG_DIRECT || it == TAG_BYPASS || it.equals("direct", true) }) {
            return KIND_DIRECT
        }
        return KIND_PROXY
    }
}
