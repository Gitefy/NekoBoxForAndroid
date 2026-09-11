package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.aidl.RequestFlowData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RequestSnapshotPublisherTest {
    @Test
    fun oldDrainCannotClearOrStealNewSession() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val delivered = CopyOnWriteArrayList<String>()
        val publisher = RequestSnapshotPublisher(
            scope = this,
            isActive = { true },
            deliver = { batch ->
                val id = batch.items.first().id
                if (id == "old") {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                }
                delivered.add(id)
            },
        )
        publisher.submit(RequestFlowBatch(arrayListOf(RequestFlowData(id = "old")), 1L))
        oldStarted.await()
        publisher.shutdown()
        publisher.submit(RequestFlowBatch(arrayListOf(RequestFlowData(id = "new")), 2L))
        repeat(40) {
            if (delivered.contains("new")) return@repeat
            yield()
        }
        releaseOld.complete(Unit)
        repeat(20) { yield() }
        assertTrue(delivered.contains("new"))
        assertEquals(1, delivered.count { it == "new" })
        publisher.shutdown()
    }
}
