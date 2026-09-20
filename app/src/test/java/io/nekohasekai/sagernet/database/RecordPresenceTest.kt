package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPresenceTest {
    @Test
    fun countMatchesListEmptinessForZeroOneAndMany() {
        assertPresence(emptyList())
        assertPresence(listOf(1L))
        assertPresence(listOf(1L, 2L, 3L))
    }

    @Test
    fun emptyMemberReconcileMatchesOriginalGetAllLogic() {
        assertFalse(RecordPresence.needsEmptyMemberReconcile(0, 0))
        assertTrue(RecordPresence.needsEmptyMemberReconcile(0, 1))
        assertTrue(RecordPresence.needsEmptyMemberReconcile(0, 8))
        assertFalse(RecordPresence.needsEmptyMemberReconcile(1, 8))
        assertFalse(RecordPresence.needsEmptyMemberReconcile(3, 0))
    }

    @Test
    fun memoryDaoCountMatchesAllAndGetAll() {
        val members = MemoryMembers()
        val proxies = MemoryProxies()
        assertEquals(members.all().isEmpty(), members.count() == 0L)
        assertEquals(proxies.getAll().isEmpty(), proxies.count() == 0L)

        members.insert(listOf(RouterMember(1, 10)))
        proxies.rows += 10L
        assertEquals(1L, members.count())
        assertEquals(1L, proxies.count())
        assertEquals(members.all().isNotEmpty(), RecordPresence.hasAny(members.count()))
        assertEquals(proxies.getAll().isNotEmpty(), RecordPresence.hasAny(proxies.count()))

        members.insert(listOf(RouterMember(1, 11), RouterMember(2, 12)))
        proxies.rows += 11L
        proxies.rows += 12L
        assertEquals(3L, members.count())
        assertEquals(3L, proxies.count())
        assertEquals(members.all().size.toLong(), members.count())
        assertEquals(proxies.getAll().size.toLong(), proxies.count())
        assertFalse(RecordPresence.needsEmptyMemberReconcile(members.count(), proxies.count()))
    }

    private fun assertPresence(ids: List<Long>) {
        assertEquals(ids.isNotEmpty(), RecordPresence.hasAny(ids.size.toLong()))
        assertEquals(ids.isEmpty(), ids.size.toLong() == 0L)
    }

    private class MemoryMembers : RouterMember.Dao {
        private val rows = mutableListOf<RouterMember>()
        override fun all() = rows.toList()
        override fun count() = rows.size.toLong()
        override fun getByRouter(routerId: Long) = rows.filter { it.routerId == routerId }
        override fun deleteByRouter(routerId: Long): Int = error("unused")
        override fun deleteByProxy(proxyId: Long): Int = error("unused")
        override fun updateUserOrder(routerId: Long, proxyId: Long, userOrder: Long): Int = error("unused")
        override fun insert(members: List<RouterMember>) { rows.addAll(members) }
        override fun reset() { rows.clear() }
    }

    private class MemoryProxies {
        val rows = mutableListOf<Long>()
        fun getAll() = rows.toList()
        fun count() = rows.size.toLong()
    }
}
