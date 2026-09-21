package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TrafficUpdaterTest {
    @Test
    fun firstSampleUsesElapsedSamplingTimeAndQueriesEachTagOnce() {
        var now = 40_000L
        var calls = 0
        val first = TrafficUpdater.TrafficLooperData("node", rx = 100L)
        val second = TrafficUpdater.TrafficLooperData("node")
        val updater = TrafficUpdater(
            queryStats = { _, _ -> calls++; 2_000L },
            items = listOf(first, second),
            monotonicMillis = { now },
        )
        now += 2_000L
        updater.updateAll()
        assertEquals(2, calls)
        assertEquals(1_000L, first.rxRate)
        assertEquals(first.rxRate, second.rxRate)
        assertEquals(2_100L, first.rx)
        assertTrue(first.hasTrafficDelta)
    }

    @Test
    fun identicalClockReadingDoesNotConsumeCounters() {
        var now = 10L
        var calls = 0
        val item = TrafficUpdater.TrafficLooperData("node")
        val updater = TrafficUpdater({ _, _ -> calls++; 50L }, listOf(item), { now })
        updater.updateAll()
        assertEquals(0, calls)
        now += 1_000L
        updater.updateAll()
        assertEquals(50L, item.rx)
        assertEquals(50L, item.rxRate)
    }

    @Test
    fun batchSnapshotDecodesIndexedDeltasInSingleCall() {
        var now = 10_000L
        var snapshotCalls = 0

        val itemProxy = TrafficUpdater.TrafficLooperData(tag = "proxy", rx = 100L, tx = 200L)
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = "bypass", rx = 50L, tx = 10L)
        val itemIgnored = TrafficUpdater.TrafficLooperData(tag = "other", ignore = true)

        val indexed = arrayOf<TrafficUpdater.TrafficLooperData?>(itemProxy, itemBypass, itemIgnored)

        fun createSnapshotBytes(rx0: Long, tx0: Long, rx1: Long, tx1: Long): ByteArray {
            val buf = ByteBuffer.allocate(1 + 2 + 2 * 18 + 2)
            buf.put(1.toByte()) // version
            buf.putShort(2.toShort()) // 2 indexed entries
            // Entry 0: index 0 (proxy)
            buf.putShort(0.toShort())
            buf.putLong(rx0)
            buf.putLong(tx0)
            // Entry 1: index 1 (bypass)
            buf.putShort(1.toShort())
            buf.putLong(rx1)
            buf.putLong(tx1)
            // 0 named entries
            buf.putShort(0.toShort())
            return buf.array()
        }

        val updater = TrafficUpdater(
            items = listOf(itemProxy, itemBypass, itemIgnored),
            monotonicMillis = { now },
            batchSnapshot = {
                snapshotCalls++
                createSnapshotBytes(rx0 = 1_000L, tx0 = 2_000L, rx1 = 300L, tx1 = 400L)
            },
            indexedItems = indexed,
        )

        now += 1_000L
        updater.updateAll()

        assertEquals(1, snapshotCalls)
        assertEquals(1_100L, itemProxy.rx)
        assertEquals(2_200L, itemProxy.tx)
        assertEquals(1_000L, itemProxy.rxRate)
        assertEquals(2_000L, itemProxy.txRate)
        assertTrue(itemProxy.hasTrafficDelta)

        assertEquals(350L, itemBypass.rx)
        assertEquals(410L, itemBypass.tx)
        assertEquals(300L, itemBypass.rxRate)
        assertEquals(400L, itemBypass.txRate)
        assertTrue(itemBypass.hasTrafficDelta)

        assertFalse(itemIgnored.hasTrafficDelta)
    }

    @Test
    fun batchSnapshotDecodesNamedEntriesWhenUnindexed() {
        var now = 10_000L
        val itemNamed = TrafficUpdater.TrafficLooperData(tag = "custom-route")
        val tagMap = mapOf("custom-route" to itemNamed)

        val tagBytes = "custom-route".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 2 + 2 + 2 + tagBytes.size + 16)
        buf.put(1.toByte()) // version
        buf.putShort(0.toShort()) // 0 indexed
        buf.putShort(1.toShort()) // 1 named
        buf.putShort(tagBytes.size.toShort())
        buf.put(tagBytes)
        buf.putLong(500L) // rx
        buf.putLong(600L) // tx

        val updater = TrafficUpdater(
            items = listOf(itemNamed),
            monotonicMillis = { now },
            batchSnapshot = { buf.array() },
            indexedItems = emptyArray(),
            tagToItem = tagMap,
        )

        now += 2_000L
        updater.updateAll()

        assertEquals(500L, itemNamed.rx)
        assertEquals(600L, itemNamed.tx)
        assertEquals(250L, itemNamed.rxRate)
        assertEquals(300L, itemNamed.txRate)
        assertTrue(itemNamed.hasTrafficDelta)
    }

    @Test
    fun batchSnapshotFallbackToLegacyWhenSnapshotFails() {
        var now = 10_000L
        var legacyCalls = 0
        val item = TrafficUpdater.TrafficLooperData("node")

        val updater = TrafficUpdater(
            queryStats = { _, _ -> legacyCalls++; 150L },
            items = listOf(item),
            monotonicMillis = { now },
            batchSnapshot = { throw RuntimeException("JNI error") },
        )

        now += 1_000L
        updater.updateAll()

        assertEquals(2, legacyCalls)
        assertEquals(150L, item.rx)
        assertEquals(150L, item.tx)
        assertTrue(item.hasTrafficDelta)
        assertEquals(1, updater.fallbackCount.get())
    }

    @Test
    fun normalBatchSnapshotHasZeroFallbackCount() {
        var now = 10_000L
        val item = TrafficUpdater.TrafficLooperData("proxy")
        val buf = ByteBuffer.allocate(5)
        buf.put(1.toByte())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        val updater = TrafficUpdater(
            items = listOf(item),
            monotonicMillis = { now },
            batchSnapshot = { buf.array() },
            indexedItems = arrayOf(item),
        )

        now += 1_000L
        updater.updateAll()
        assertEquals(0, updater.fallbackCount.get())
    }

    @Test
    fun malformedBatchSnapshotAfterResetDoesNotInvokeLegacyQuery() {
        var now = 10_000L
        var legacyCalls = 0
        val item = TrafficUpdater.TrafficLooperData("node")

        val updater = TrafficUpdater(
            queryStats = { _, _ -> legacyCalls++; 150L },
            items = listOf(item),
            monotonicMillis = { now },
            batchSnapshot = { byteArrayOf(99) }, // unsupported version
        )

        now += 1_000L
        updater.updateAll()

        assertEquals(0, legacyCalls)
        assertEquals(0L, item.rx)
        assertEquals(0L, item.tx)
        assertFalse(item.hasTrafficDelta)
    }

    @Test
    fun batchSnapshotAppliesSameDeltaToAllConsumersSharingTagOrIndex() {
        var now = 10_000L
        val itemA = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = 1L)
        val itemB = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = 2L)
        val itemC = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = 3L)

        val indexedConsumers = arrayOf<List<TrafficUpdater.TrafficLooperData>?>(
            listOf(itemA, itemB, itemC)
        )

        val buf = ByteBuffer.allocate(1 + 2 + 18 + 2)
        buf.put(1.toByte()) // version
        buf.putShort(1.toShort()) // 1 indexed entry
        buf.putShort(0.toShort()) // index 0 ("c-10")
        buf.putLong(1000L) // rx
        buf.putLong(2000L) // tx
        buf.putShort(0.toShort()) // 0 named entries

        val updater = TrafficUpdater(
            items = listOf(itemA, itemB, itemC),
            monotonicMillis = { now },
            batchSnapshot = { buf.array() },
            indexedConsumers = indexedConsumers,
        )

        now += 1_000L
        updater.updateAll()

        assertEquals(1000L, itemA.rx)
        assertEquals(2000L, itemA.tx)
        assertEquals(1000L, itemA.rxRate)
        assertEquals(2000L, itemA.txRate)
        assertTrue(itemA.hasTrafficDelta)

        assertEquals(1000L, itemB.rx)
        assertEquals(2000L, itemB.tx)
        assertEquals(1000L, itemB.rxRate)
        assertEquals(2000L, itemB.txRate)
        assertTrue(itemB.hasTrafficDelta)

        assertEquals(1000L, itemC.rx)
        assertEquals(2000L, itemC.tx)
        assertEquals(1000L, itemC.rxRate)
        assertEquals(2000L, itemC.txRate)
        assertTrue(itemC.hasTrafficDelta)
    }
}
