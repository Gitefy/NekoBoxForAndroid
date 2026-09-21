package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.ktx.Logs
import java.nio.ByteBuffer

class TrafficUpdater(
    private val queryStats: ((String, String) -> Long)? = null,
    val items: List<TrafficLooperData>, // contain "bypass"
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val batchSnapshot: (() -> ByteArray?)? = null,
    private val indexedItems: Array<TrafficLooperData?>? = null,
    private val tagToItem: Map<String, TrafficLooperData>? = null,
) {
    companion object {
        private fun makeSnapshotSupplier(box: libcore.BoxInstance): () -> ByteArray? = {
            runCatching { box.trafficStatsSnapshot() }.getOrNull()
        }
    }

    constructor(box: libcore.BoxInstance, items: List<TrafficLooperData>) :
        this(
            queryStats = box::queryStats,
            items = items,
            batchSnapshot = makeSnapshotSupplier(box),
        )

    constructor(
        box: libcore.BoxInstance,
        items: List<TrafficLooperData>,
        indexedItems: Array<TrafficLooperData?>?,
        tagToItem: Map<String, TrafficLooperData>?,
    ) : this(
        queryStats = box::queryStats,
        items = items,
        batchSnapshot = makeSnapshotSupplier(box),
        indexedItems = indexedItems,
        tagToItem = tagToItem,
    )

    init {
        val now = monotonicMillis()
        items.forEach { it.lastUpdate = now }
    }

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var profileId: Long = 0L,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
        var hasTrafficDelta: Boolean = false,
    )

    private fun applyDelta(item: TrafficLooperData, rx: Long, tx: Long) {
        val now = monotonicMillis()
        val interval = (now - item.lastUpdate).coerceAtLeast(1L)
        item.lastUpdate = now
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000L / interval
        item.txRate = tx * 1000L / interval
        item.hasTrafficDelta = rx != 0L || tx != 0L
    }

    private fun updateOneLegacy(item: TrafficLooperData, out: TrafficLooperData): TrafficLooperData {
        val now = monotonicMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        out.tag = item.tag
        if (interval <= 0) {
            item.rxRate = 0
            item.txRate = 0
            out.rx = 0L
            out.tx = 0L
            out.rxRate = 0L
            out.txRate = 0L
            return out
        }

        val q = queryStats ?: return out
        val tx = q(item.tag, "uplink")
        val rx = q(item.tag, "downlink")

        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000L / interval
        item.txRate = tx * 1000L / interval

        out.rx = rx
        out.tx = tx
        out.rxRate = item.rxRate
        out.txRate = item.txRate
        return out
    }

    private val diffByTag = HashMap<String, TrafficLooperData>()
    private val queriedTags = HashSet<String>()

    private fun updateAllLegacy() {
        queriedTags.clear()
        for (i in items.indices) {
            val item = items[i]
            item.hasTrafficDelta = false
            if (item.ignore) continue
            val tag = item.tag
            if (queriedTags.add(tag)) {
                val diff = diffByTag.getOrPut(tag) { TrafficLooperData(tag = tag) }
                updateOneLegacy(item, diff)
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
            } else {
                val diff = diffByTag[tag]!!
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
                item.lastUpdate = monotonicMillis()
            }
        }
    }

    val fallbackCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun updateAll() {
        if (batchSnapshot == null) {
            fallbackCount.incrementAndGet()
            updateAllLegacy()
            return
        }

        val bytes = try {
            batchSnapshot.invoke()
        } catch (e: Exception) {
            fallbackCount.incrementAndGet()
            Logs.w("P3_D_TRAFFIC_BATCH_FALLBACK: ${e.message}")
            updateAllLegacy()
            return
        }

        if (bytes == null || bytes.isEmpty()) {
            if (queryStats != null && items.isNotEmpty()) {
                fallbackCount.incrementAndGet()
                Logs.w("P3_D_TRAFFIC_BATCH_FALLBACK: empty snapshot bytes")
                updateAllLegacy()
            } else {
                for (i in items.indices) {
                    val item = items[i]
                    item.hasTrafficDelta = false
                    item.rxRate = 0L
                    item.txRate = 0L
                }
            }
            return
        }

        // Reset per-tick delta and rates on all items
        for (i in items.indices) {
            val item = items[i]
            item.hasTrafficDelta = false
            item.rxRate = 0L
            item.txRate = 0L
        }

        try {
            val buf = ByteBuffer.wrap(bytes)
            val version = buf.get().toInt()
            if (version != 1) {
                fallbackCount.incrementAndGet()
                Logs.w("P3_D_TRAFFIC_BATCH_FALLBACK: unsupported version $version")
                updateAllLegacy()
                return
            }

            val indexedCount = buf.short.toInt() and 0xFFFF
            for (i in 0 until indexedCount) {
                val idx = buf.short.toInt() and 0xFFFF
                val rx = buf.long
                val tx = buf.long
                val item = indexedItems?.getOrNull(idx) ?: continue
                if (!item.ignore) {
                    applyDelta(item, rx, tx)
                }
            }

            val namedCount = buf.short.toInt() and 0xFFFF
            for (i in 0 until namedCount) {
                val tagLen = buf.short.toInt() and 0xFFFF
                val tagBytes = ByteArray(tagLen)
                buf.get(tagBytes)
                val rx = buf.long
                val tx = buf.long
                val tagName = String(tagBytes, Charsets.UTF_8)
                val item = tagToItem?.get(tagName) ?: continue
                if (!item.ignore) {
                    applyDelta(item, rx, tx)
                }
            }
        } catch (e: Exception) {
            fallbackCount.incrementAndGet()
            Logs.w("P3_D_TRAFFIC_BATCH_FALLBACK: decode error ${e.message}")
            updateAllLegacy()
        }
    }
}
