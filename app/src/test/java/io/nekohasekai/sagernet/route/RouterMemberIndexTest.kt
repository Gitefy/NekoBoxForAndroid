package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RouterMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [RouterMemberIndex.groupByRouter] produces results identical
 * to calling `getByRouter(id)` N times for each router.
 *
 * These tests act as the equivalence proof for the P3-0 change in
 * captureConfigSnapshot: one `routerMemberDao.all()` + groupByRouter()
 * must produce the same result as N × `routerMemberDao.getByRouter(id)`.
 */
class RouterMemberIndexTest {

    // -------------------------------------------------------------------------
    // 0 routers
    // -------------------------------------------------------------------------

    @Test
    fun zeroRouters_emptyResult() {
        val result = RouterMemberIndex.groupByRouter(emptyList())
        assertTrue("Expected empty map for zero members", result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 1 router
    // -------------------------------------------------------------------------

    @Test
    fun oneRouter_membersGroupedCorrectly() {
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 10L, userOrder = 1L),
            RouterMember(routerId = 1L, proxyId = 20L, userOrder = 2L),
        )
        val result = RouterMemberIndex.groupByRouter(members)
        assertEquals(setOf(1L), result.keys)
        assertEquals(listOf(10L, 20L), result[1L]!!.map { it.proxyId })
    }

    // -------------------------------------------------------------------------
    // Multiple routers — simple case
    // -------------------------------------------------------------------------

    @Test
    fun multipleRouters_eachGroupHasCorrectMembers() {
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 10L, userOrder = 1L),
            RouterMember(routerId = 2L, proxyId = 20L, userOrder = 1L),
            RouterMember(routerId = 1L, proxyId = 30L, userOrder = 2L),
            RouterMember(routerId = 2L, proxyId = 40L, userOrder = 2L),
        )
        val result = RouterMemberIndex.groupByRouter(members)
        assertEquals(setOf(1L, 2L), result.keys)
        assertEquals(listOf(10L, 30L), result[1L]!!.map { it.proxyId })
        assertEquals(listOf(20L, 40L), result[2L]!!.map { it.proxyId })
    }

    // -------------------------------------------------------------------------
    // Interleaved members (DAO returns them mixed across routers)
    // -------------------------------------------------------------------------

    @Test
    fun interleavedMembers_groupedCorrectlyRegardlessOfInputOrder() {
        // Simulate all() returning rows interleaved (router 1 and 2 mixed)
        val members = listOf(
            RouterMember(routerId = 2L, proxyId = 99L, userOrder = 1L),
            RouterMember(routerId = 1L, proxyId = 11L, userOrder = 1L),
            RouterMember(routerId = 2L, proxyId = 88L, userOrder = 2L),
            RouterMember(routerId = 1L, proxyId = 22L, userOrder = 2L),
        )
        val result = RouterMemberIndex.groupByRouter(members)
        // Each router's list must be sorted by userOrder,proxyId — not encounter order
        assertEquals(listOf(11L, 22L), result[1L]!!.map { it.proxyId })
        assertEquals(listOf(99L, 88L), result[2L]!!.map { it.proxyId })
    }

    // -------------------------------------------------------------------------
    // Reverse DAO order (highest userOrder appears first in input list)
    // -------------------------------------------------------------------------

    @Test
    fun reverseDAOOrder_sortedAscendingByUserOrder() {
        // Input has descending userOrder — output must be ascending
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 30L, userOrder = 3L),
            RouterMember(routerId = 1L, proxyId = 20L, userOrder = 2L),
            RouterMember(routerId = 1L, proxyId = 10L, userOrder = 1L),
        )
        val result = RouterMemberIndex.groupByRouter(members)
        assertEquals(listOf(10L, 20L, 30L), result[1L]!!.map { it.proxyId })
    }

    // -------------------------------------------------------------------------
    // Dangling members (proxyId that the caller won't find in proxies map)
    // -------------------------------------------------------------------------

    @Test
    fun danglingMembers_stillPresentInIndex_callerResponsibleForFiltering() {
        // A dangling member is one whose proxyId is not in the loaded proxies map.
        // RouterMemberIndex must NOT silently drop it — the caller (captureConfigSnapshot
        // via loadEntities) handles the miss. This preserves parity with getByRouter.
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 10L, userOrder = 1L),
            RouterMember(routerId = 1L, proxyId = 999L, userOrder = 2L), // dangling
        )
        val result = RouterMemberIndex.groupByRouter(members)
        assertEquals(
            "Dangling member must appear in index output (not filtered here)",
            listOf(10L, 999L),
            result[1L]!!.map { it.proxyId },
        )
    }

    // -------------------------------------------------------------------------
    // Shared proxy (same proxyId belongs to two routers)
    // -------------------------------------------------------------------------

    @Test
    fun sharedProxy_appearsInBothRouterGroups() {
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 50L, userOrder = 1L),
            RouterMember(routerId = 2L, proxyId = 50L, userOrder = 1L), // same proxy
            RouterMember(routerId = 2L, proxyId = 60L, userOrder = 2L),
        )
        val result = RouterMemberIndex.groupByRouter(members)
        assertEquals(listOf(50L), result[1L]!!.map { it.proxyId })
        assertEquals(listOf(50L, 60L), result[2L]!!.map { it.proxyId })
    }

    // -------------------------------------------------------------------------
    // Same userOrder → stable secondary sort by proxyId
    // -------------------------------------------------------------------------

    @Test
    fun sameUserOrder_sortedByProxyIdAscending() {
        // When userOrder ties, getByRouter uses ORDER BY userOrder, proxyId
        // so smaller proxyId must come first.
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 300L, userOrder = 5L),
            RouterMember(routerId = 1L, proxyId = 100L, userOrder = 5L),
            RouterMember(routerId = 1L, proxyId = 200L, userOrder = 5L),
        )
        val result = RouterMemberIndex.groupByRouter(members)
        assertEquals(listOf(100L, 200L, 300L), result[1L]!!.map { it.proxyId })
    }

    // -------------------------------------------------------------------------
    // Filter parity: only enabled router IDs are retained (captureConfigSnapshot logic)
    // -------------------------------------------------------------------------

    @Test
    fun filterToEnabledRouterIds_disabledRouterMembersDropped() {
        // Mirrors the filtering done in captureConfigSnapshot:
        //   val enabledRouterIds = enabledRouters.mapTo(HashSet()) { it.id }
        //   grouped.filterKeys { it in enabledRouterIds }
        val members = listOf(
            RouterMember(routerId = 1L, proxyId = 10L, userOrder = 1L),
            RouterMember(routerId = 2L, proxyId = 20L, userOrder = 1L), // disabled router
        )
        val grouped = RouterMemberIndex.groupByRouter(members)
        val enabledIds = setOf(1L) // router 2 is disabled
        val filtered = grouped.filterKeys { it in enabledIds }
        assertEquals(setOf(1L), filtered.keys)
        assertEquals(listOf(10L), filtered[1L]!!.map { it.proxyId })
    }
}
