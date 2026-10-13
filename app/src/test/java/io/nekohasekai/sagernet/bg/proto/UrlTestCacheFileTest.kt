package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UrlTestCacheFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun concurrentTestBoxesUseDistinctCacheFilesAndCleanThemUp() {
        val first = UrlTestCacheFile.create(temporaryFolder.root)
        val second = UrlTestCacheFile.create(temporaryFolder.root)
        val firstPath = first.path
        val secondPath = second.path

        try {
            val isolated = JsonParser.parseString(
                first.applyTo("""{"experimental":{"cache_file":{"enabled":false}},"route":{"final":"direct"}}""")
            ).asJsonObject
            val batch = JsonParser.parseString(second.applyTo("""{"route":{"final":"direct"}}""")).asJsonObject

            assertNotEquals(firstPath, secondPath)
            assertEquals(firstPath, isolated.getAsJsonObject("experimental").getAsJsonObject("cache_file").get("path").asString)
            assertFalse(isolated.getAsJsonObject("experimental").getAsJsonObject("cache_file").get("enabled").asBoolean)
            assertEquals(secondPath, batch.getAsJsonObject("experimental").getAsJsonObject("cache_file").get("path").asString)
            assertFalse(batch.getAsJsonObject("experimental").getAsJsonObject("cache_file").get("enabled").asBoolean)
            assertEquals("direct", batch.getAsJsonObject("route").get("final").asString)
            assertTrue(File(firstPath).isFile)
            assertTrue(File(secondPath).isFile)
        } finally {
            first.close()
            second.close()
        }

        assertFalse(File(firstPath).exists())
        assertFalse(File(secondPath).exists())
    }
}
