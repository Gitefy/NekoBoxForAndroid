package io.nekohasekai.sagernet.ui

data class SubscriptionUserinfoStats(
    val used: Long = 0L,
    val total: Long = 0L,
    val expireEpochSec: Long? = null,
)

object SubscriptionUserinfoParser {
    private val upload = Regex("upload=([0-9]+)")
    private val download = Regex("download=([0-9]+)")
    private val total = Regex("total=([0-9]+)")
    private val expire = Regex("expire=([0-9]+)")

    fun firstGroup(regex: Regex, text: String): String? =
        regex.findAll(text).mapNotNull {
            if (it.groupValues.size > 1) it.groupValues[1] else null
        }.firstOrNull()

    fun parse(text: String): SubscriptionUserinfoStats {
        var used = 0L
        firstGroup(upload, text)?.let { used += it.toLong() }
        firstGroup(download, text)?.let { used += it.toLong() }
        val totalVal = firstGroup(total, text)?.toLong() ?: 0L
        val expireVal = firstGroup(expire, text)?.toLong()
        return SubscriptionUserinfoStats(used, totalVal, expireVal)
    }
}
