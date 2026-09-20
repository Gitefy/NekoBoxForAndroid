package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.route.RouterMemberIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterMemberSnapshotBuilderTest {
    @Test
    fun emptyRoutersAndMembersYieldEmptySnapshot() {
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = emptyList(),
            members = emptyList(),
            proxiesById = emptyMap(),
            groupsById = emptyMap(),
        )
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun singleMemberResolvesProxyAndSourceGroup() {
        val router = RouterGroup(id = 7L, stableTag = "router.us", name = "US")
        val group = ProxyGroup(id = 10L, name = "sub")
        val proxy = ProxyEntity(id = 1L, groupId = 10L, uuid = "node-1")
        val members = listOf(RouterMember(routerId = 7L, proxyId = 1L, userOrder = 1L))
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(router),
            members = members,
            proxiesById = mapOf(1L to proxy),
            groupsById = mapOf(10L to group),
        )
        assertEquals(listOf(1L), snapshot[7L]?.map { it.proxyId })
        assertEquals("node-1", snapshot[7L]?.single()?.stableId)
        assertEquals(10L, snapshot[7L]?.single()?.sourceGroupId)
        assertEquals(1L, snapshot[7L]?.single()?.userOrder)
    }

    @Test
    fun missingProxyIsDroppedLikeOriginalMapNotNull() {
        val router = RouterGroup(id = 7L, stableTag = "router.us", name = "US")
        val members = listOf(
            RouterMember(routerId = 7L, proxyId = 1L, userOrder = 1L),
            RouterMember(routerId = 7L, proxyId = 99L, userOrder = 2L),
        )
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(router),
            members = members,
            proxiesById = mapOf(1L to ProxyEntity(id = 1L, groupId = 10L, uuid = "n1")),
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(1L), snapshot[7L]?.map { it.proxyId })
    }

    @Test
    fun multipleRoutersKeepGetByRouterOrder() {
        val us = RouterGroup(id = 1L, stableTag = "router.us", name = "US")
        val sg = RouterGroup(id = 2L, stableTag = "router.sg", name = "SG")
        val jp = RouterGroup(id = 3L, stableTag = "router.jp", name = "JP")
        val members = listOf(
            RouterMember(1L, 30, 2),
            RouterMember(1L, 10, 1),
            RouterMember(2L, 20, 1),
            RouterMember(2L, 21, 2),
        )
        val ordered = members.sortedWith(
            compareBy<RouterMember> { it.routerId }.thenBy { it.userOrder }.thenBy { it.proxyId }
        )
        val grouped = RouterMemberIndex.groupByRouter(ordered)
        assertEquals(listOf(10L, 30L), grouped[1L]?.map { it.proxyId })
        assertEquals(listOf(20L, 21L), grouped[2L]?.map { it.proxyId })
        val proxies = listOf(10L, 30L, 20L, 21L).associateWith {
            ProxyEntity(id = it, groupId = 10L, uuid = "n$it")
        }
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(us, sg, jp),
            members = ordered,
            proxiesById = proxies,
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(10L, 30L), snapshot[1L]?.map { it.proxyId })
        assertEquals(listOf(20L, 21L), snapshot[2L]?.map { it.proxyId })
        assertEquals(emptyList<Long>(), snapshot[3L]?.map { it.proxyId })
    }

    @Test
    fun reversedProxyLookupDoesNotReorderMembers() {
        val router = RouterGroup(id = 1L, stableTag = "router.us", name = "US")
        val members = listOf(
            RouterMember(1L, 10, 1),
            RouterMember(1L, 30, 2),
            RouterMember(1L, 20, 3),
        )
        val proxies = listOf(20L, 30L, 10L).map { ProxyEntity(id = it, groupId = 10L, uuid = "n$it") }
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(router),
            members = members,
            proxiesById = proxies.associateBy { it.id },
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(10L, 30L, 20L), snapshot[1L]?.map { it.proxyId })
    }

    @Test
    fun chunkBoundariesKeepUserOrderAfterShuffledInResults() {
        val router = RouterGroup(id = 1L, stableTag = "router.us", name = "US")
        val group = ProxyGroup(id = 10L, name = "g")
        for (count in listOf(1, 499, 500, 501)) {
            val members = (1..count).map { i ->
                RouterMember(routerId = 1L, proxyId = i.toLong(), userOrder = i.toLong())
            }
            val ids = members.map { it.proxyId }
            val loaded = QueryIdChunks.load(ids) { chunk ->
                chunk.asReversed().map { id -> ProxyEntity(id = id, groupId = 10L, uuid = "n$id") }
            }
            val snapshot = RouterMemberSnapshotBuilder.build(
                routers = listOf(router),
                members = members,
                proxiesById = loaded.associateBy { it.id },
                groupsById = mapOf(10L to group),
            )
            assertEquals(ids, snapshot[1L]?.map { it.proxyId })
        }
    }

    @Test
    fun multiChunkShuffledResultsStillFollowUserOrder() {
        val count = QueryIdChunks.SIZE + 7
        val members = (1..count).map { i ->
            RouterMember(routerId = 1L, proxyId = i.toLong(), userOrder = i.toLong())
        }
        val loaded = QueryIdChunks.load(members.map { it.proxyId }) { chunk ->
            chunk.shuffled(kotlin.random.Random(0)).map { id ->
                ProxyEntity(id = id, groupId = 10L, uuid = "n$id")
            }
        }
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(RouterGroup(id = 1L, stableTag = "router.us", name = "US")),
            members = members,
            proxiesById = loaded.associateBy { it.id },
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(members.map { it.proxyId }, snapshot[1L]?.map { it.proxyId })
    }

    @Test
    fun danglingMembersDropWithoutReorderingSurvivors() {
        val members = listOf(
            RouterMember(1L, 10, 1),
            RouterMember(1L, 99, 2),
            RouterMember(1L, 20, 3),
            RouterMember(1L, 98, 4),
            RouterMember(1L, 30, 5),
        )
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(RouterGroup(id = 1L, stableTag = "router.us", name = "US")),
            members = members,
            proxiesById = listOf(30L, 10L, 20L).associateWith {
                ProxyEntity(id = it, groupId = 10L, uuid = "n$it")
            },
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(10L, 20L, 30L), snapshot[1L]?.map { it.proxyId })
    }

    @Test
    fun twoRoutersCanShareAProxyWithoutSharingOrder() {
        val members = listOf(
            RouterMember(1L, 10, 2),
            RouterMember(1L, 20, 1),
            RouterMember(2L, 10, 1),
        )
        val proxies = listOf(10L, 20L).associateWith {
            ProxyEntity(id = it, groupId = 10L, uuid = "n$it")
        }
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(
                RouterGroup(id = 1L, stableTag = "router.us", name = "US"),
                RouterGroup(id = 2L, stableTag = "router.sg", name = "SG"),
            ),
            members = members,
            proxiesById = proxies,
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(20L, 10L), snapshot[1L]?.map { it.proxyId })
        assertEquals(listOf(10L), snapshot[2L]?.map { it.proxyId })
    }

    @Test
    fun duplicateMemberRowsAreEmittedPerInputRoomPrimaryKeyPreventsThis() {
        // router_members PK is (routerId, proxyId). Builder does not silently
        // collapse duplicates if they are passed in.
        val members = listOf(
            RouterMember(1L, 10, 1),
            RouterMember(1L, 10, 1),
        )
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(RouterGroup(id = 1L, stableTag = "router.us", name = "US")),
            members = members,
            proxiesById = mapOf(10L to ProxyEntity(id = 10L, groupId = 10L, uuid = "n10")),
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(10L, 10L), snapshot[1L]?.map { it.proxyId })
    }

    @Test
    fun interleavedMemberRowsSortByUserOrderNotEncounterOrder() {
        val members = listOf(
            RouterMember(2L, 21, 2),
            RouterMember(1L, 30, 2),
            RouterMember(2L, 20, 1),
            RouterMember(1L, 10, 1),
        )
        val snapshot = RouterMemberSnapshotBuilder.build(
            routers = listOf(
                RouterGroup(id = 1L, stableTag = "router.us", name = "US"),
                RouterGroup(id = 2L, stableTag = "router.sg", name = "SG"),
            ),
            members = members,
            proxiesById = listOf(10L, 30L, 20L, 21L).associateWith {
                ProxyEntity(id = it, groupId = 10L, uuid = "n$it")
            },
            groupsById = mapOf(10L to ProxyGroup(id = 10L, name = "g")),
        )
        assertEquals(listOf(10L, 30L), snapshot[1L]?.map { it.proxyId })
        assertEquals(listOf(20L, 21L), snapshot[2L]?.map { it.proxyId })
    }
}

class RouterMemberIndexSizeTest {
    @Test
    fun emptyMembersYieldZeroCounts() {
        assertEquals(
            emptyMap<Long, Int>(),
            RouterMemberIndex.sizesByRouterId(emptyList(), emptyList()),
        )
        assertEquals(
            mapOf(1L to 0),
            RouterMemberIndex.sizesByRouterId(listOf(1L), emptyList()),
        )
    }

    @Test
    fun singleRouterCountMatchesGetByRouterSize() {
        val members = listOf(RouterMember(routerId = 7L, proxyId = 1L, userOrder = 1L))
        assertEquals(mapOf(7L to 1), RouterMemberIndex.sizesByRouterId(listOf(7L), members))
    }

    @Test
    fun multipleRoutersSkipEmptyAndKeepCounts() {
        val members = listOf(
            RouterMember(1L, 10, 1),
            RouterMember(1L, 30, 2),
            RouterMember(2L, 20, 1),
        )
        assertEquals(
            mapOf(1L to 2, 2L to 1, 3L to 0),
            RouterMemberIndex.sizesByRouterId(listOf(1L, 2L, 3L), members),
        )
    }
}

class QueryIdChunksTest {
    @Test
    fun emptyIdsSkipDao() {
        var calls = 0
        val loaded = QueryIdChunks.load(emptyList()) { chunk ->
            calls++
            chunk.map { it }
        }
        assertEquals(0, calls)
        assertEquals(emptyList<Long>(), loaded)
    }

    @Test
    fun singleChunkPassesDistinctIds() {
        val loaded = QueryIdChunks.load(listOf(1L, 1L, 2L)) { it }
        assertEquals(listOf(1L, 2L), loaded)
    }

    @Test
    fun chunksAboveLimit() {
        val ids = (1L..QueryIdChunks.SIZE + 3L).toList()
        var calls = 0
        val loaded = QueryIdChunks.load(ids) { chunk ->
            calls++
            assertTrue(chunk.size <= QueryIdChunks.SIZE)
            chunk
        }
        assertEquals(2, calls)
        assertEquals(ids, loaded)
    }

    @Test
    fun chunkSizesAtBoundaries() {
        for ((count, expectedCalls) in listOf(499 to 1, 500 to 1, 501 to 2)) {
            var calls = 0
            val ids = (1L..count.toLong()).toList()
            val loaded = QueryIdChunks.load(ids) { chunk ->
                calls++
                chunk.asReversed()
            }
            assertEquals(expectedCalls, calls)
            assertEquals(count, loaded.size)
            assertEquals(ids.toSet(), loaded.toSet())
        }
    }
}
