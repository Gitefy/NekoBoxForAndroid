package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSelectionUiRefreshTest {
    @Test
    fun refreshesPreviousAndNextWhenBothValid() {
        assertEquals(setOf(10L, 20L), RouterSelectionUiRefresh.changedIds(10L, 20L))
    }

    @Test
    fun firstSelectionOnlyRefreshesNext() {
        assertEquals(setOf(20L), RouterSelectionUiRefresh.changedIds(-1L, 20L))
        assertEquals(setOf(20L), RouterSelectionUiRefresh.changedIds(0L, 20L))
    }

    @Test
    fun sameNodeStillRequestsThatRow() {
        assertEquals(setOf(20L), RouterSelectionUiRefresh.changedIds(20L, 20L))
    }

    @Test
    fun fallsBackWhenClickedNodeIsMissingFromVisibleList() {
        assertTrue(RouterSelectionUiRefresh.needsFullRefresh(listOf(1L, 2L), 99L))
        assertFalse(RouterSelectionUiRefresh.needsFullRefresh(listOf(1L, 99L, 2L), 99L))
        assertFalse(RouterSelectionUiRefresh.needsFullRefresh(listOf(1L), 0L))
    }
}
