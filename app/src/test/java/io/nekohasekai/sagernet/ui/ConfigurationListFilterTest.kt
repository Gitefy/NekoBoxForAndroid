package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationListFilterTest {
    @Test
    fun matchIsCaseInsensitiveAcrossNameTypeAndAddress() {
        assertTrue(ConfigurationListFilter.matches("us", "US-1", "vmess", "1.1.1.1:443"))
        assertTrue(ConfigurationListFilter.matches("VMESS", "n1", "vmess", ""))
        assertTrue(ConfigurationListFilter.matches("1.1.1.1", "n1", "ss", "1.1.1.1:443"))
        assertFalse(ConfigurationListFilter.matches("jp", "US-1", "vmess", "1.1.1.1:443"))
    }

    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(ConfigurationListFilter.matches("", "anything", "x", "y"))
    }

    @Test
    fun skipsNotifyWhenIdsAndOrderUnchanged() {
        val ids = listOf(3L, 1L, 2L)
        assertFalse(ConfigurationListFilter.shouldNotify(ids, listOf(3L, 1L, 2L)))
        assertTrue(ConfigurationListFilter.shouldNotify(ids, listOf(3L, 2L, 1L)))
        assertTrue(ConfigurationListFilter.shouldNotify(ids, listOf(3L, 1L)))
    }

    @Test
    fun emptyQueryReloadsUntilAlreadyUnfiltered() {
        assertTrue(ConfigurationListFilter.shouldReloadUnfiltered(null, ""))
        assertTrue(ConfigurationListFilter.shouldReloadUnfiltered("us", ""))
        assertFalse(ConfigurationListFilter.shouldReloadUnfiltered("", ""))
        assertFalse(ConfigurationListFilter.shouldReloadUnfiltered("", "us"))
        assertFalse(ConfigurationListFilter.shouldReloadUnfiltered(null, "us"))
    }

    @Test
    fun narrowingQueryKeepsRelativeOrderOfMatches() {
        val rows = listOf(
            Triple("US-2", "ss", "2.2.2.2"),
            Triple("JP-1", "vmess", "3.3.3.3"),
            Triple("US-1", "ss", "1.1.1.1"),
        )
        val ids = listOf(10L, 20L, 30L)
        val matched = ids.zip(rows).mapNotNull { (id, row) ->
            id.takeIf { ConfigurationListFilter.matches("us", row.first, row.second, row.third) }
        }
        assertEquals(listOf(10L, 30L), matched)
    }
}
