package io.nekohasekai.sagernet.database

/** Existence/count checks that must stay equivalent to List.isEmpty()/isNotEmpty(). */
object RecordPresence {
    fun hasAny(count: Long): Boolean = count > 0L

    fun needsEmptyMemberReconcile(memberCount: Long, proxyCount: Long): Boolean =
        memberCount == 0L && proxyCount > 0L
}
