package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StopCleanupTest {
    @Test fun failureDoesNotPreventRemainingCleanupAndReportsAllErrors() = runBlocking {
        val actions = mutableListOf<String>()
        val first = IOException("stats write failed")
        val second = IOException("native close failed")
        val failure = runStopCleanup(
            { actions += "stats"; throw first },
            { actions += "native"; throw second },
            { actions += "network" },
        )
        assertEquals(listOf("stats", "native", "network"), actions)
        assertSame(first, failure)
        assertArrayEquals(arrayOf(second), failure!!.suppressed)
    }

    @Test fun cancellationStillAttemptsEveryCleanupStep() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var resourcesReleased = false
        val stop = launch {
            runStopCleanup(
                { entered.complete(Unit); release.await() },
                { resourcesReleased = true },
            )
        }
        entered.await()
        stop.cancel()
        release.complete(Unit)
        stop.join()
        assertTrue(resourcesReleased)
    }
}
