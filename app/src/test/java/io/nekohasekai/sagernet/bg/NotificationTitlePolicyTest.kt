package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.http.HttpBean
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTitlePolicyTest {

    @Test
    fun returnsAppNameWhenShowProfileIsDisabled() {
        val proxy = ProxyEntity(id = 1L, groupId = 10L, userOrder = 0).apply {
            putBean(HttpBean().apply {
                serverAddress = "example.com"
                name = "Sensitive Airport Node"
            })
        }
        val title = ServiceNotification.genTitle(
            ent = proxy,
            showProfileInNotification = false,
            showGroupInNotification = true,
            groupNameProvider = { "VIP Group" },
            fallbackAppName = "NekoBox",
        )
        assertEquals("NekoBox", title)
    }

    @Test
    fun returnsNodeNameWhenShowGroupIsDisabled() {
        val proxy = ProxyEntity(id = 1L, groupId = 10L, userOrder = 0).apply {
            putBean(HttpBean().apply {
                serverAddress = "example.com"
                name = "Node A"
            })
        }
        val title = ServiceNotification.genTitle(
            ent = proxy,
            showProfileInNotification = true,
            showGroupInNotification = false,
            groupNameProvider = { "VIP Group" },
            fallbackAppName = "NekoBox",
        )
        assertEquals("Node A", title)
    }

    @Test
    fun returnsGroupAndNodeNameWhenBothAreEnabled() {
        val proxy = ProxyEntity(id = 1L, groupId = 10L, userOrder = 0).apply {
            putBean(HttpBean().apply {
                serverAddress = "example.com"
                name = "Node A"
            })
        }
        val title = ServiceNotification.genTitle(
            ent = proxy,
            showProfileInNotification = true,
            showGroupInNotification = true,
            groupNameProvider = { "VIP Group" },
            fallbackAppName = "NekoBox",
        )
        assertEquals("[VIP Group] Node A", title)
    }

    @Test
    fun returnsAppNameWhenProxyIsNull() {
        val title = ServiceNotification.genTitle(
            ent = null,
            showProfileInNotification = true,
            showGroupInNotification = true,
            groupNameProvider = { "VIP Group" },
            fallbackAppName = "NekoBox",
        )
        assertEquals("NekoBox", title)
    }
}
