package io.nekohasekai.sagernet.widget

object OutboundPreferenceSummary {
    fun resolve(
        value: String?,
        profileName: String?,
        routerName: String?,
        routerMissing: Boolean,
        invalidRouterLabel: String,
        fallback: CharSequence?,
    ): CharSequence? = when (value) {
        OutboundPreference.VALUE_SELECT_PROFILE -> profileName ?: fallback
        OutboundPreference.VALUE_SELECT_ROUTER -> when {
            routerName != null -> routerName
            routerMissing -> invalidRouterLabel
            else -> fallback
        }
        else -> fallback
    }

    fun cacheKey(value: String?, profileId: Long, routerId: Long): String = when (value) {
        OutboundPreference.VALUE_SELECT_PROFILE -> "p:$profileId"
        OutboundPreference.VALUE_SELECT_ROUTER -> "r:$routerId"
        else -> value.orEmpty()
    }
}
