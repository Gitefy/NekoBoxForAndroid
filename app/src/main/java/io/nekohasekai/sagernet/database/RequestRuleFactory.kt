package io.nekohasekai.sagernet.database

data class RequestRuleDraft(
    val name: String,
    val domains: String = "",
    val ip: String = "",
    val packages: Set<String> = emptySet(),
    val outbound: Long,
    val routerGroupId: Long = 0L,
    val enabled: Boolean = true,
) {
    fun toRuleEntity(): RuleEntity = RuleEntity(
        name = name,
        domains = domains,
        ip = ip,
        packages = packages,
        outbound = outbound,
        routerGroupId = routerGroupId,
        enabled = enabled,
    )
}

object RequestRuleFactory {
    enum class MatchKind { EXACT_DOMAIN, DOMAIN_SUFFIX, APP, DEST_IP }
    enum class OutboundKind { DIRECT, REJECT, PROXY, ROUTER }

    fun build(
        match: MatchKind,
        outboundKind: OutboundKind,
        domain: String,
        packageName: String,
        destinationIp: String,
        routerStableTag: String,
        routersByStableTag: Map<String, Long>,
    ): RequestRuleDraft? {
        val outbound = when (outboundKind) {
            OutboundKind.PROXY -> 0L
            OutboundKind.DIRECT -> -1L
            OutboundKind.REJECT -> -2L
            OutboundKind.ROUTER -> 0L
        }
        val routerId = if (outboundKind == OutboundKind.ROUTER) {
            routersByStableTag[routerStableTag] ?: return null
        } else 0L
        val name = when (match) {
            MatchKind.EXACT_DOMAIN, MatchKind.DOMAIN_SUFFIX -> domain.ifBlank { return null }
            MatchKind.APP -> packageName.ifBlank { return null }
            MatchKind.DEST_IP -> destinationIp.ifBlank { return null }
        }
        return RequestRuleDraft(
            name = name,
            domains = when (match) {
                MatchKind.EXACT_DOMAIN -> "full:$domain"
                MatchKind.DOMAIN_SUFFIX -> "domain:$domain"
                else -> ""
            },
            ip = if (match == MatchKind.DEST_IP) destinationIp else "",
            packages = if (match == MatchKind.APP) setOf(packageName) else emptySet(),
            outbound = outbound,
            routerGroupId = routerId,
        )
    }
}
