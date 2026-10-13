package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RestoreCoordinator
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import java.util.concurrent.Callable

/** Read and merge the latest rows under the same SQLite transaction as writes. */
internal fun <T> withCurrentSubscriptionProfiles(
    database: SagerDatabase,
    groupId: Long,
    expectedGroup: ByteArray,
    commit: (List<ProxyEntity>) -> T,
): T = database.runInTransaction(Callable {
    val current = database.groupDao().getById(groupId)
    if (current == null || !expectedGroup.contentEquals(KryoConverters.serialize(current))) {
        throw RestoreCoordinator.SubscriptionWriteRejected("Subscription settings changed during download")
    }
    commit(database.proxyDao().getByGroup(groupId))
})
