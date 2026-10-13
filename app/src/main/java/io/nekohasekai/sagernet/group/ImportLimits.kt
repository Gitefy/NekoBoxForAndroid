package io.nekohasekai.sagernet.group

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object ImportLimits {
    const val SUBSCRIPTION_BYTES = 8 * 1024 * 1024
    const val BACKUP_BYTES = 32 * 1024 * 1024
    const val PROFILES = 10_000
    const val BACKUP_RECORDS = 100_000

    fun readBytes(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0) { "Invalid import size limit" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            require(count <= limit - output.size()) { "Import exceeds size limit ($limit bytes)" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun checkSubscription(text: String) {
        require(text.length <= SUBSCRIPTION_BYTES && text.toByteArray(Charsets.UTF_8).size <= SUBSCRIPTION_BYTES) {
            "Subscription exceeds size limit ($SUBSCRIPTION_BYTES bytes)"
        }
    }

    fun parseYaml(text: String): Map<String, Any?> {
        checkSubscription(text)
        val options = LoaderOptions().apply {
            codePointLimit = SUBSCRIPTION_BYTES
            nestingDepthLimit = 50
            maxAliasesForCollections = 50
            isAllowDuplicateKeys = false
        }
        return Yaml(SafeConstructor(options)).load<Map<String, Any?>>(text)
            ?: error("Empty YAML subscription")
    }
}
