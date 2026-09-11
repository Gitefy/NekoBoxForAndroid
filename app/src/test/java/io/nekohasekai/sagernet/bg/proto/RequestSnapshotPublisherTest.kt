package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowBatch
import io.nekohasekai.sagernet.aidl.RequestFlowData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSnapshotPublisherTest {
    @Test
    fun slowReceiverDoesNotQueueUnboundedWorkers() = runBlocking {
        val publisher = RequestSnapshotPublisher(
            scope = this,
            isActive = { true },
            deliver = { delay(20) },
        )
        repeat(20) { index ->
            publisher.submit(
                RequestFlowBatch(arrayListOf(RequestFlowData(id = "$index")), runtimeGeneration = 1L),
            )
        }
        yield()
        delay(80)
        publisher.shutdown()
        assertTrue("launches=${publisher.launches}", publisher.launches <= 3)
    }
}
