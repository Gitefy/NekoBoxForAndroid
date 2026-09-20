package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RouterMember

/**
 * Groups router_members rows the same way as N times `getByRouter`.
 * Order within each router is always `userOrder, proxyId` — never the order of
 * a SQL `IN` / `getEntities` result, and not raw encounter order if rows are
 * interleaved.
 */
object RouterMemberIndex {
    fun groupByRouter(members: List<RouterMember>): Map<Long, List<RouterMember>> {
        val grouped = LinkedHashMap<Long, MutableList<RouterMember>>()
        for (member in members) {
            grouped.getOrPut(member.routerId) { mutableListOf() }.add(member)
        }
        val byUserOrder = compareBy<RouterMember> { it.userOrder }.thenBy { it.proxyId }
        grouped.values.forEach { list -> list.sortWith(byUserOrder) }
        return grouped
    }

    /** Same result as N times `getByRouter(id).size` over [routerIds]. */
    fun sizesByRouterId(
        routerIds: Collection<Long>,
        members: List<RouterMember>,
    ): Map<Long, Int> {
        val grouped = groupByRouter(members)
        return routerIds.associateWith { grouped[it]?.size ?: 0 }
    }
}
