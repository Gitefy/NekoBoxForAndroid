package io.nekohasekai.sagernet.route

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterRuntimeSelectionTest {

    @Test
    fun mapsCoreWinnerToItsActualProfileInsteadOfTheFirstMember() {
        val selections = RouterRuntimeSelection.resolve(
            routerTags = linkedMapOf(10L to "router.web3"),
            profileTags = linkedMapOf(1L to "node-a", 2L to "node-b"),
            currentOutbound = { "node-b" },
        )

        assertArrayEquals(longArrayOf(10L, 2L), selections)
    }

    @Test
    fun leavesGroupUnselectedUntilCoreHasAResult() {
        val selections = RouterRuntimeSelection.resolve(
            routerTags = linkedMapOf(10L to "router.web3"),
            profileTags = linkedMapOf(1L to "node-a", 2L to "node-b"),
            currentOutbound = { "" },
        )

        assertArrayEquals(longArrayOf(), selections)
    }

    @Test
    fun parsesSelectionPairsWithoutAcceptingDanglingValues() {
        assertEquals(
            linkedMapOf(10L to 2L, 20L to 4L),
            RouterRuntimeSelection.toMap(longArrayOf(10L, 2L, 20L, 4L, 99L)),
        )
    }

    @Test
    fun parsesTabDelimitedGroupSelections() {
        val raw = "router.g1\tnode-a\nrouter.g2\tnode-b\nrouter.empty\t\n"
        val parsed = RouterRuntimeSelection.parseGroupSelections(raw)
        assertEquals(mapOf("router.g1" to "node-a", "router.g2" to "node-b"), parsed)
    }

    @Test
    fun resolveBatchExecutesSingleBatchCallAndMatchesLegacy() {
        val routerTags = linkedMapOf(
            10L to "router.g1",
            20L to "router.g2",
            30L to "router.g3",
        )
        val profileTags = linkedMapOf(
            1L to "node-1",
            2L to "node-2",
            3L to "node-3",
        )
        val winners = mapOf(
            "router.g1" to "node-2",
            "router.g2" to "node-3",
            "router.g3" to "node-1",
        )

        var batchCalls = 0
        val batchSelections = RouterRuntimeSelection.resolveBatch(
            routerTags = routerTags,
            profileTags = profileTags,
            batchQuery = { tags ->
                batchCalls++
                assertEquals(listOf("router.g1", "router.g2", "router.g3"), tags)
                winners
            },
        )

        val legacySelections = RouterRuntimeSelection.resolve(
            routerTags = routerTags,
            profileTags = profileTags,
            currentOutbound = { winners[it] ?: "" },
        )

        assertEquals(1, batchCalls)
        assertArrayEquals(longArrayOf(10L, 2L, 20L, 3L, 30L, 1L), batchSelections)
        assertArrayEquals(legacySelections, batchSelections)
    }

    @Test
    fun resolveBatchEmptyGroupsReturnsEmpty() {
        var batchCalls = 0
        val selections = RouterRuntimeSelection.resolveBatch(
            routerTags = emptyMap(),
            profileTags = mapOf(1L to "node-1"),
            batchQuery = { batchCalls++; emptyMap() },
        )
        assertEquals(0, batchCalls)
        assertArrayEquals(longArrayOf(), selections)
    }

    @Test
    fun resolveBatchWithMixedChangesAndUnknownGroup() {
        val routerTags = linkedMapOf(
            100L to "selector-a",
            200L to "urltest-b",
            300L to "router-c",
        )
        val profileTags = linkedMapOf(
            1L to "node-a1",
            2L to "node-a2",
            3L to "node-b1",
            4L to "node-b2",
            5L to "node-c1",
        )

        // Baseline: selector-a was 1L (node-a1), urltest-b was 3L (node-b1), router-c was 5L (node-c1)
        // Now:
        // selector-a -> node-a2 (changed!)
        // urltest-b  -> node-b2 (changed!)
        // router-c   -> node-c1 (unchanged!)
        // "unknown-group" -> "node-unknown" (invalid/unknown group)
        val winners = mapOf(
            "selector-a" to "node-a2",
            "urltest-b" to "node-b2",
            "router-c" to "node-c1",
            "unknown-group" to "node-unknown",
        )

        val selections = RouterRuntimeSelection.resolveBatch(
            routerTags = routerTags,
            profileTags = profileTags,
            batchQuery = { winners },
        )

        val resultMap = RouterRuntimeSelection.toMap(selections)
        assertEquals(2L, resultMap[100L]) // selector-a correctly updated to node-a2
        assertEquals(4L, resultMap[200L]) // urltest-b correctly updated to node-b2
        assertEquals(5L, resultMap[300L]) // router-c correctly kept at node-c1
        assertEquals(3, resultMap.size) // unknown group did not corrupt result
        assertArrayEquals(longArrayOf(100L, 2L, 200L, 4L, 300L, 5L), selections)
    }
}
