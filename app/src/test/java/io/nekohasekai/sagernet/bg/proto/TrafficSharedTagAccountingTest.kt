package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TrafficSharedTagAccountingTest {

    private fun createSnapshotBytes(entries: List<Triple<Int, Long, Long>>): ByteArray {
        val buf = ByteBuffer.allocate(1 + 2 + entries.size * 18 + 2)
        buf.put(1.toByte()) // version
        buf.putShort(entries.size.toShort())
        for ((idx, rx, tx) in entries) {
            buf.putShort(idx.toShort())
            buf.putLong(rx)
            buf.putLong(tx)
        }
        buf.putShort(0.toShort()) // 0 named entries
        return buf.array()
    }

    @Test
    fun sharedTagAllConsumersReceiveSameDeltaWithLegacyEquivalence() {
        var now = 100_000L

        // Profiles A, B, C sharing tag "c-10" (e.g. hops in a chain)
        val entA = ProxyEntity(id = 11L).apply { rx = 500L; tx = 600L }
        val entB = ProxyEntity(id = 12L).apply { rx = 500L; tx = 600L }
        val entC = ProxyEntity(id = 13L).apply { rx = 500L; tx = 600L }

        val itemA = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = entA.id, rx = entA.rx, tx = entA.tx, rxBase = entA.rx, txBase = entA.tx)
        val itemB = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = entB.id, rx = entB.rx, tx = entB.tx, rxBase = entB.rx, txBase = entB.tx)
        val itemC = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = entC.id, rx = entC.rx, tx = entC.tx, rxBase = entC.rx, txBase = entC.tx)

        val consumers = listOf(itemA, itemB, itemC)
        val indexedConsumers = arrayOf<List<TrafficUpdater.TrafficLooperData>?>(
            null, // idx 0
            null, // idx 1
            consumers, // idx 2 ("c-10")
        )

        val batchUpdater = TrafficUpdater(
            items = consumers,
            monotonicMillis = { now },
            batchSnapshot = {
                createSnapshotBytes(listOf(Triple(2, 1000L, 2000L)))
            },
            indexedConsumers = indexedConsumers,
        )

        now += 1_000L
        batchUpdater.updateAll()

        // 1. Verify batch snapshot delta applied to all 3 entities
        for (item in consumers) {
            assertEquals(1500L, item.rx)
            assertEquals(2600L, item.tx)
            assertEquals(1000L, item.rxRate)
            assertEquals(2000L, item.txRate)
            assertTrue(item.hasTrafficDelta)
        }

        // 2. Verify legacy equivalence: running legacy query on fresh items yields identical result
        now = 100_000L
        val legA = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = entA.id, rx = entA.rx, tx = entA.tx)
        val legB = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = entB.id, rx = entB.rx, tx = entB.tx)
        val legC = TrafficUpdater.TrafficLooperData(tag = "c-10", profileId = entC.id, rx = entC.rx, tx = entC.tx)
        val legacyUpdater = TrafficUpdater(
            queryStats = { tag, direct ->
                if (tag == "c-10") if (direct == "downlink") 1000L else 2000L else 0L
            },
            items = listOf(legA, legB, legC),
            monotonicMillis = { now },
        )
        now += 1_000L
        legacyUpdater.updateAll()

        for (i in consumers.indices) {
            assertEquals(consumers[i].rx, listOf(legA, legB, legC)[i].rx)
            assertEquals(consumers[i].tx, listOf(legA, legB, legC)[i].tx)
            assertEquals(consumers[i].rxRate, listOf(legA, legB, legC)[i].rxRate)
            assertEquals(consumers[i].txRate, listOf(legA, legB, legC)[i].txRate)
            assertEquals(consumers[i].hasTrafficDelta, listOf(legA, legB, legC)[i].hasTrafficDelta)
        }

        // 3. Verify final flush: update proxy entities from idMap
        val idMap = mapOf(entA.id to itemA, entB.id to itemB, entC.id to itemC)
        val trafficToSave = mutableListOf<Triple<Long, Long, Long>>()
        val trafficMap = mapOf("c-10" to listOf(entA, entB, entC))

        trafficMap.forEach { (_, ents) ->
            for (ent in ents) {
                val item = idMap[ent.id]!!
                ent.rx = item.rx
                ent.tx = item.tx
                trafficToSave.add(Triple(ent.id, ent.rx, ent.tx))
            }
        }

        assertEquals(3, trafficToSave.size)
        assertEquals(Triple(11L, 1500L, 2600L), trafficToSave[0])
        assertEquals(Triple(12L, 1500L, 2600L), trafficToSave[1])
        assertEquals(Triple(13L, 1500L, 2600L), trafficToSave[2])

        // 4. Verify main tunnel speed aggregation: counts each physical stats tag once
        var mainTxRate = 0L
        var mainRxRate = 0L
        var mainTx = 0L
        var mainRx = 0L
        val tagConsumersMap = mapOf("c-10" to consumers)
        tagConsumersMap.values.forEach { consumerList ->
            val it = consumerList.firstOrNull() ?: return@forEach
            if (!it.ignore) {
                mainTxRate += it.txRate
                mainRxRate += it.rxRate
            }
            mainTx += it.tx - it.txBase
            mainRx += it.rx - it.rxBase
        }
        assertEquals(2000L, mainTxRate) // NOT 6000L
        assertEquals(1000L, mainRxRate) // NOT 3000L
        assertEquals(2000L, mainTx)
        assertEquals(1000L, mainRx)

        // 5. Verify resetTraffic: reset entB only
        val targetIds = hashSetOf(entB.id)
        val changed = linkedMapOf<Long, TrafficData>()
        idMap.forEach { (id, item) ->
            if (id > 0L && id !in targetIds && item.hasTrafficDelta) {
                changed[id] = TrafficData(id = id, rx = item.rx, tx = item.tx)
            }
        }
        trafficMap.values.forEach { entities ->
            entities.forEach { entity ->
                if (entity.id in targetIds) {
                    entity.tx = 0L
                    entity.rx = 0L
                }
            }
        }
        targetIds.forEach { id ->
            idMap[id]?.apply {
                tx = 0L
                rx = 0L
                txBase = 0L
                rxBase = 0L
                txRate = 0L
                rxRate = 0L
                hasTrafficDelta = false
            }
            changed[id] = TrafficData(id = id, rx = 0L, tx = 0L)
        }

        // B is reset
        assertEquals(0L, itemB.rx)
        assertEquals(0L, itemB.tx)
        assertEquals(0L, entB.rx)
        assertEquals(0L, entB.tx)
        assertFalse(itemB.hasTrafficDelta)

        // A and C remain unchanged
        assertEquals(1500L, itemA.rx)
        assertEquals(2600L, itemA.tx)
        assertEquals(1500L, entA.rx)
        assertEquals(2600L, entA.tx)
        assertEquals(1500L, itemC.rx)
        assertEquals(2600L, itemC.tx)
        assertEquals(1500L, entC.rx)
        assertEquals(2600L, entC.tx)
    }

    @Test
    fun dynamicMainSelectorIsolatesInactiveMembersAndPreservesRouterMembers() {
        var now = 10_000L

        // Main selector profiles: P1 (winner) and P2 (inactive)
        val itemWinner = TrafficUpdater.TrafficLooperData(tag = TAG_PROXY, profileId = 1L, ignore = false)
        val itemInactive = TrafficUpdater.TrafficLooperData(tag = "p2-tag", profileId = 2L, ignore = true)

        // Independent Router group member: P3 (must NOT be suppressed)
        val itemRouter = TrafficUpdater.TrafficLooperData(tag = "router-tag", profileId = 3L, ignore = false)

        val indexedConsumers = arrayOf<List<TrafficUpdater.TrafficLooperData>?>(
            listOf(itemWinner), // idx 0: TAG_PROXY
            null, // idx 1: TAG_BYPASS
            listOf(itemInactive), // idx 2: p2-tag
            listOf(itemRouter), // idx 3: router-tag
        )

        val batchUpdater = TrafficUpdater(
            items = listOf(itemWinner, itemInactive, itemRouter),
            monotonicMillis = { now },
            batchSnapshot = {
                createSnapshotBytes(listOf(
                    Triple(0, 5000L, 6000L), // TAG_PROXY delta
                    Triple(2, 3000L, 4000L), // inactive node delta (simulated spurious stat)
                    Triple(3, 7000L, 8000L), // router node delta
                ))
            },
            indexedConsumers = indexedConsumers,
        )

        now += 1_000L
        batchUpdater.updateAll()

        // Active winner received TAG_PROXY traffic
        assertEquals(5000L, itemWinner.rx)
        assertEquals(6000L, itemWinner.tx)
        assertTrue(itemWinner.hasTrafficDelta)

        // Inactive member ignored and did NOT accumulate traffic
        assertEquals(0L, itemInactive.rx)
        assertEquals(0L, itemInactive.tx)
        assertFalse(itemInactive.hasTrafficDelta)

        // Independent router member accumulated traffic independently
        assertEquals(7000L, itemRouter.rx)
        assertEquals(8000L, itemRouter.tx)
        assertTrue(itemRouter.hasTrafficDelta)
    }
}
