package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RouterGroup

data class RouterStartInputs(
    val mode: Int,
    val uiSelectedId: Long?,
    val persistedSelectedId: Long,
    val routerId: Long,
)

object RouterStartSeed {
    fun resolve(
        mode: Int,
        uiSelectedId: Long?,
        persistedSelectedId: Long,
        memberIdsInOrder: List<Long>,
    ): Long {
        val members = memberIdsInOrder.filter { it > 0L }.distinct()
        val memberSet = members.toSet()
        uiSelectedId?.takeIf { it > 0L && it in memberSet }?.let { return it }
        persistedSelectedId.takeIf { it > 0L && it in memberSet }?.let { return it }
        if (mode == RouterGroup.MODE_URL_TEST) {
            return members.firstOrNull() ?: 0L
        }
        return 0L
    }

    fun resolve(inputs: RouterStartInputs, memberIdsInOrder: List<Long>): Long =
        resolve(inputs.mode, inputs.uiSelectedId, inputs.persistedSelectedId, memberIdsInOrder)
}
