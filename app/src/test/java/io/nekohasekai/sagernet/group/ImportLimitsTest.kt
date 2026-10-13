package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ImportLimitsTest {
    @Test
    fun acceptsNormalYamlAndExplicitStrings() {
        val result = ImportLimits.parseYaml("proxies:\n  - name: !!str 123\n    port: 443\n")
        val proxy = (result["proxies"] as List<*>).single() as Map<*, *>
        assertEquals("123", proxy["name"])
        assertEquals(443, proxy["port"])
    }

    @Test
    fun rejectsJavaObjectTags() {
        assertThrows(Exception::class.java) {
            ImportLimits.parseYaml("proxies: []\nobject: !!java.util.Date '2000-01-01'\n")
        }
    }

    @Test
    fun rejectsDuplicateKeysAndExcessiveNesting() {
        assertThrows(Exception::class.java) { ImportLimits.parseYaml("proxies: []\nproxies: []\n") }
        assertThrows(Exception::class.java) {
            ImportLimits.parseYaml("proxies: " + "[".repeat(60) + "0" + "]".repeat(60))
        }
    }

    @Test
    fun rejectsAliasExpansion() {
        val yaml = "node: &node [1]\nproxies: [" + List(51) { "*node" }.joinToString(",") + "]"
        assertThrows(Exception::class.java) { ImportLimits.parseYaml(yaml) }
    }

    @Test
    fun byteLimitAcceptsBoundaryAndRejectsOneMoreByte() {
        assertArrayEquals(byteArrayOf(1, 2), ImportLimits.readBytes(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        assertThrows(IllegalArgumentException::class.java) {
            ImportLimits.readBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
        }
        assertArrayEquals(byteArrayOf(), ImportLimits.readBytes(ByteArrayInputStream(byteArrayOf()), 0))
    }

    @Test
    fun zipLimitAppliesToUncompressedBytes() {
        val compressed = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use {
                it.putNextEntry(ZipEntry("backup.json"))
                it.write(ByteArray(8192))
                it.closeEntry()
            }
        }.toByteArray()
        assertTrue(compressed.size < 1024)
        ZipInputStream(ByteArrayInputStream(compressed)).use {
            it.nextEntry
            assertThrows(IllegalArgumentException::class.java) { ImportLimits.readBytes(it, 1024) }
        }
    }

    @Test
    fun subscriptionLimitCountsUtf8Bytes() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportLimits.checkSubscription("中".repeat(ImportLimits.SUBSCRIPTION_BYTES / 3 + 1))
        }
    }

    @Test
    fun rejectsPayloadHiddenInZipDirectory() {
        val compressed = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use {
                it.putNextEntry(ZipEntry("directory/"))
                it.write(byteArrayOf(1))
                it.closeEntry()
            }
        }.toByteArray()
        ZipInputStream(ByteArrayInputStream(compressed)).use {
            assertTrue(it.nextEntry.isDirectory)
            assertThrows(IllegalArgumentException::class.java) { ImportLimits.readBytes(it, 0) }
        }
    }
}
