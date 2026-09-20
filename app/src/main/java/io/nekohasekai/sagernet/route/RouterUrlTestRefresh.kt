package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RouterGroup

object RouterUrlTestRefresh {
    fun tagFor(
        mode: Int?,
        enabled: Boolean,
        stableTag: String?,
        serviceConnected: Boolean,
    ): String? {
        if (!serviceConnected) return null
        if (mode != RouterGroup.MODE_URL_TEST || !enabled) return null
        return stableTag?.takeIf { it.isNotBlank() }
    }

    fun tagFor(routerGroup: RouterGroup?, serviceConnected: Boolean): String? = tagFor(
        mode = routerGroup?.mode,
        enabled = routerGroup?.enabled == true,
        stableTag = routerGroup?.stableTag,
        serviceConnected = serviceConnected,
    )

    fun canRefreshRunningGroup(groupTag: String, urlTestTags: Collection<String>): Boolean =
        groupTag.isNotBlank() && groupTag in urlTestTags
}
