package io.nekohasekai.sagernet.database.preference

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-SNAPSHOT-ORDERING regression.
 *
 * Forces the interleaving from the audit receipt:
 *  A captures epoch and reads k=old but is paused before merge;
 *  DB snapshot source becomes k=new;
 *  B performs a complete read+merge of new;
 *  A resumes and merges k=old.
 *
 * Without serializing capture+read+merge the mirror regresses to k=old.
 * With the snapshot coordinator the final value must remain k=new.
 */
class RoomPreferenceDataStoreSnapshotOrderingTest {

    private fun row(key: String, value: String): KeyValuePair =
        KeyValuePair(key).put(value)

    private fun fakeDao(): KeyValuePair.Dao = object : KeyValuePair.Dao {
        override fun all(): List<KeyValuePair> = emptyList()
        override fun get(key: String): KeyValuePair? = null
        override fun put(value: KeyValuePair): Long = 1L
        override fun delete(key: String): Int = 0
        override fun reset(): Int = 0
        override fun insert(list: List<KeyValuePair>) {}
    }

    @Test
    fun concurrentSnapshotMergesDoNotRegressToStaleSnapshot() {
        val snapshotOld = listOf(row("k", "old"))
        val snapshotNew = listOf(row("k", "new"))
        val current = AtomicReference(snapshotOld)
        val aCaptured = CountDownLatch(1)
        val aMayProceed = CountDownLatch(1)
        val blockNextRead = AtomicBoolean(false)

        val tableSnapshot: () -> List<KeyValuePair> = {
            if (blockNextRead.getAndSet(false)) {
                val captured = current.get()
                aCaptured.countDown()
                assertTrue("A was not released in time", aMayProceed.await(5, TimeUnit.SECONDS))
                captured
            } else {
                current.get()
            }
        }

        val store = RoomPreferenceDataStore(fakeDao(), tableSnapshot = tableSnapshot)
        runBlocking { store.awaitReady() }
        assertEquals("old", store.getString("k"))
        blockNextRead.set(true)

        val aThread = Thread {
            runBlocking { store.syncNow() }
        }.apply { isDaemon = true; start() }

        assertTrue("A did not reach snapshot read", aCaptured.await(5, TimeUnit.SECONDS))

        current.set(snapshotNew)

        val bThread = Thread {
            runBlocking { store.syncNow() }
        }.apply { isDaemon = true; start() }

        Thread.sleep(80)
        aMayProceed.countDown()

        aThread.join(5000)
        bThread.join(5000)
        assertEquals("new", store.getString("k"))
    }
}