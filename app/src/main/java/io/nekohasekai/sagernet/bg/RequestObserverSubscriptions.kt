package io.nekohasekai.sagernet.bg

import java.util.concurrent.ConcurrentHashMap

class RequestObserverSubscriptions {
    private val subscribers = ConcurrentHashMap.newKeySet<Any>()

    fun enable(client: Any): Boolean {
        subscribers.add(client)
        return hasSubscribers()
    }

    fun disable(client: Any): Boolean {
        subscribers.remove(client)
        return hasSubscribers()
    }

    fun remove(client: Any): Boolean = disable(client)

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    fun contains(client: Any): Boolean = subscribers.contains(client)

    fun clear() {
        subscribers.clear()
    }
}
