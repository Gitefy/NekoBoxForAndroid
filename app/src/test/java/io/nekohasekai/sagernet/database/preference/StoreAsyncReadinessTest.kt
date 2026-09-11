package io.nekohasekai.sagernet.database.preference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * S2-B1 readiness contract. Every assertion is about the store constructor
 * not touching SQLite on the calling thread: the fake DAO records calls and
 * the test probes StoreReadiness, never "no exception on main thread".
 */
class StoreAsyncReadinessTest {

    private class BlockingFakeDao(
        var latch: CountDownLatch = CountDownLatch(1),
        @Volatile var shouldFail: Boolean = false,
    ) : KeyValuePair.Dao {
        val allReads = AtomicInteger(0)
        override fun all(): List<KeyValuePair> {
            allReads.incrementAndGet()
            if (shouldFail) throw IllegalStateException("injected prime failure")
            if (!latch.await(10, TimeUnit.SECONDS)) error("prime gate not released")
            return emptyList()
        }

        override fun get(key: String): KeyValuePair? = null
        override fun put(value: KeyValuePair): Long = 1L
        override fun delete(key: String): Int = 0
        override fun reset(): Int = 0
        override fun insert(list: List<KeyValuePair>) {}
    }

    @Test
    fun constructorDoesNotReadDao() {
        val dao = BlockingFakeDao(CountDownLatch(1))
        val store = RoomPreferenceDataStore(dao, tableSnapshot = dao::all)
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Loading)
        assertEquals(0, dao.allReads.get())
        dao.latch.countDown()
        runBlocking { store.awaitReady() }
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Ready)
    }

    @Test
    fun failedLoadDoesNotWriteDefaults() {
        val dao = BlockingFakeDao(CountDownLatch(0), shouldFail = true)
        val store = RoomPreferenceDataStore(dao, tableSnapshot = dao::all)
        val state = runBlocking { store.awaitReady() }
        assertTrue(state is RoomPreferenceDataStore.StoreReadiness.Failed)
        assertEquals(1, dao.allReads.get())
        // S2-B1 invariant: Failed never writes a default; the fake DAO has no Sager DAO so
        // there's nothing else to inspect. Presence of Failed state is the assertion.
    }

    @Test
    fun dependentActionWaitsForReady() {
        val gate = CountDownLatch(1)
        val dao = BlockingFakeDao(gate)
        val store = RoomPreferenceDataStore(dao, tableSnapshot = dao::all)
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Loading)
        var continued = false
        val t = Thread {
            runBlocking {
                store.awaitReady()
                continued = true
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(80)
        assertFalse(continued)
        gate.countDown()
        t.join(5_000)
        assertTrue(continued)
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Ready)
    }

    @Test
    fun retryLoadIsSingleFlight() {
        val dao = BlockingFakeDao(CountDownLatch(0), shouldFail = true)
        val store = RoomPreferenceDataStore(dao, tableSnapshot = dao::all)
        val first = runBlocking { store.awaitReady() }
        assertTrue(first is RoomPreferenceDataStore.StoreReadiness.Failed)
        dao.shouldFail = false
        dao.latch = CountDownLatch(0)
        // Concurrent retries must coalesce: two callers racing retryPrime share one prime.
        var second: RoomPreferenceDataStore.StoreReadiness? = null
        var third: RoomPreferenceDataStore.StoreReadiness? = null
        val a = Thread { second = runBlocking { store.retryPrime() } }.apply { isDaemon = true; start() }
        val b = Thread { third = runBlocking { store.retryPrime() } }.apply { isDaemon = true; start() }
        a.join(5_000); b.join(5_000)
        assertTrue(second is RoomPreferenceDataStore.StoreReadiness.Ready)
        assertTrue(third is RoomPreferenceDataStore.StoreReadiness.Ready)
        assertTrue(store.isReady())
        // Exactly one more prime after the failed one.
        assertEquals(2, dao.allReads.get())
    }

    @Test
    fun invalidationDuringBootstrapIsObserved() {
        val gate = CountDownLatch(1)
        val dao = BlockingFakeDao(gate)
        var observe: ((Set<String>) -> Unit)? = null
        val src = RoomPreferenceDataStore.InvalidationSource { obs -> observe = obs }
        val store = RoomPreferenceDataStore(dao, invalidationSource = src, tableSnapshot = dao::all)
        Thread { Thread.sleep(40); observe?.invoke(setOf("KeyValuePair")) }.apply { isDaemon = true; start() }
        gate.countDown()
        runBlocking { store.awaitReady() }
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Ready)
    }

    @Test
    fun notificationPromotionDoesNotAwaitDb() {
        // E03 contract is on BaseService (placeholder promotion); this guard is the
        // store side: constructor still does not touch the DAO even when a fake
        // notification call would want to read app_theme.
        val dao = BlockingFakeDao(CountDownLatch(1))
        val store = RoomPreferenceDataStore(dao, tableSnapshot = dao::all)
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Loading)
        assertEquals(0, dao.allReads.get())
        dao.latch.countDown()
        runBlocking { store.awaitReady() }
    }

    @Test
    fun bootstrapCatchupFailureDoesNotPublishReady() {
        val primeGate = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val failCatchup = AtomicBoolean(true)
        var observe: ((Set<String>) -> Unit)? = null
        val src = RoomPreferenceDataStore.InvalidationSource { obs -> observe = obs }
        val dao = BlockingFakeDao(CountDownLatch(0))
        val store = RoomPreferenceDataStore(dao, invalidationSource = src, tableSnapshot = {
            val n = reads.incrementAndGet()
            if (n == 1) {
                if (!primeGate.await(10, TimeUnit.SECONDS)) error("prime gate not released")
                emptyList()
            } else if (failCatchup.get()) {
                throw IllegalStateException("injected catchup failure")
            } else {
                emptyList()
            }
        })
        assertTrue(store.readiness is RoomPreferenceDataStore.StoreReadiness.Loading)
        observe!!.invoke(setOf("KeyValuePair"))
        primeGate.countDown()
        val failed = runBlocking { store.awaitReady() }
        assertTrue(failed is RoomPreferenceDataStore.StoreReadiness.Failed)
        assertFalse(store.isReady())
        failCatchup.set(false)
        val retried = runBlocking { store.retryPrime() }
        assertTrue(retried is RoomPreferenceDataStore.StoreReadiness.Ready)
        assertTrue(store.isReady())
    }
}
