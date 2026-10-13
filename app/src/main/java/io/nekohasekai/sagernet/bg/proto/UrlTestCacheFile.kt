package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.ktx.Logs
import java.io.Closeable
import java.io.File

internal class UrlTestCacheFile private constructor(private val file: File) : Closeable {
    val path: String get() = file.absolutePath

    fun applyTo(configJson: String): String {
        val config = JsonParser.parseString(configJson).asJsonObject
        val experimental = config.getAsJsonObject("experimental") ?: JsonObject().also {
            config.add("experimental", it)
        }
        val cacheFile = experimental.getAsJsonObject("cache_file") ?: JsonObject().also {
            experimental.add("cache_file", it)
        }
        cacheFile.addProperty("enabled", false)
        cacheFile.addProperty("path", path)
        return config.toString()
    }

    override fun close() {
        if (file.exists() && !file.delete()) {
            Logs.w("URLTest temporary cache cleanup failed: $path")
        }
    }

    companion object {
        fun create(cacheDir: File): UrlTestCacheFile =
            UrlTestCacheFile(File.createTempFile("egox-urltest-", ".db", cacheDir))
    }
}
