package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.route.RouterMemberIndex
import io.nekohasekai.sagernet.route.RouterMemberSnapshot

/**
 * Builds the Router membership snapshot join without extra per-router queries.
 * [proxiesById] is a lookup only; member order comes from [members] /
 * [RouterMemberIndex] (`userOrder`, `proxyId`), matching
 * `ORDER BY routerId, userOrder, proxyId`.
 */
object RouterMemberSnapshotBuilder {
    fun build(
        routers: List<RouterGroup>,
        members: List<RouterMember>,
        proxiesById: Map<Long, ProxyEntity>,
        groupsById: Map<Long, ProxyGroup?>,
    ): Map<Long, List<RouterMemberSnapshot>> {
        val grouped = RouterMemberIndex.groupByRouter(members)
        return routers.associate { router ->
            router.id to grouped[router.id].orEmpty().mapNotNull { member ->
                proxiesById[member.proxyId]?.let { proxy ->
                    RouterMemberSnapshot(
                        proxyId = proxy.id,
                        stableId = proxy.routerStableId(),
                        sourceGroupId = groupsById[proxy.groupId]?.id,
                        userOrder = member.userOrder,
                    )
                }
            }
        }
    }
}
