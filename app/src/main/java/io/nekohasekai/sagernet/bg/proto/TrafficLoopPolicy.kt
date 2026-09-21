package io.nekohasekai.sagernet.bg.proto

object TrafficLoopPolicy {
    private const val MIN_BACKGROUND_NOTIFICATION_MILLIS = 5_000L
    private const val MIN_BACKGROUND_HIDDEN_MILLIS = 30_000L
    private const val MIN_INITIALIZATION_RETRY_MILLIS = 250L

    // Each tick queries the v2ray stats service once per tracked tag. A config
    // with several Router urltest groups (one tag per member) can easily track
    // 30+ tags; at the default 1s foreground interval that is 30+ stat queries
    // per second just for the speed display. Scale the foreground interval up
    // with tag count so the per-second polling cost stays bounded.
    private const val FOREGROUND_TICKS_MILLIS = 1_000L
    private const val TAGS_PER_EXTRA_TICK = 16
    private const val MAX_FOREGROUND_MILLIS = 3_000L

    fun shouldCollectTraffic(configuredMillis: Long, profileTrafficStatistics: Boolean): Boolean =
        configuredMillis > 0L || profileTrafficStatistics

    fun delayMillis(
        configuredMillis: Long,
        mainActivityForeground: Boolean,
        notificationSpeedVisible: Boolean,
    ): Long = delayMillis(configuredMillis, mainActivityForeground, notificationSpeedVisible, 0)

    fun delayMillis(
        configuredMillis: Long,
        mainActivityForeground: Boolean,
        notificationSpeedVisible: Boolean,
        trackedTagCount: Int,
    ): Long {
        return when {
            mainActivityForeground -> maxOf(configuredMillis, FOREGROUND_TICKS_MILLIS)
            notificationSpeedVisible -> maxOf(configuredMillis, MIN_BACKGROUND_NOTIFICATION_MILLIS)
            else -> maxOf(configuredMillis, MIN_BACKGROUND_HIDDEN_MILLIS)
        }
    }

    fun isDormant(
        mainActivityForeground: Boolean,
        notificationSpeedVisible: Boolean,
        profileTrafficStatistics: Boolean,
        hasUrlTestConsumer: Boolean,
    ): Boolean = !mainActivityForeground &&
        !notificationSpeedVisible &&
        !profileTrafficStatistics &&
        !hasUrlTestConsumer

    fun delayMillis(
        configuredMillis: Long,
        mainActivityForeground: Boolean,
        notificationSpeedVisible: Boolean,
        profileTrafficStatistics: Boolean,
        hasUrlTestConsumer: Boolean,
        trackedTagCount: Int = 0,
    ): Long {
        if (isDormant(mainActivityForeground, notificationSpeedVisible, profileTrafficStatistics, hasUrlTestConsumer)) {
            return Long.MAX_VALUE
        }
        return delayMillis(configuredMillis, mainActivityForeground, notificationSpeedVisible, trackedTagCount)
    }

    fun initializationRetryMillis(configuredMillis: Long): Long =
        maxOf(configuredMillis, MIN_INITIALIZATION_RETRY_MILLIS)
}
