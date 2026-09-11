package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    @Volatile var looper: TrafficLooper? = null
    @Volatile var connectionObserver: ConnectionObserver? = null
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d({ "Built proxy configuration: ${config.profileTagMap.size} profiles" })
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        looper = service?.let { TrafficLooper(it.data, runtimeScope) }
        looper?.start()
        connectionObserver = service?.let { svc ->
            ConnectionObserver(
                snapshot = {
                    runCatching { box.connectionSnapshot()?.value.orEmpty() }.getOrDefault("")
                },
                publish = { batch ->
                    runtimeScope.launch {
                        svc.data.binder.broadcast { it.cbRequestUpdate(batch) }
                    }
                },
                isCurrent = { svc.data.proxy === this@ProxyInstance },
                maps = {
                    val tagToName = LinkedHashMap<String, String>()
                    config.trafficMap.forEach { (tag, ents) ->
                        ents.firstOrNull()?.let { tagToName[tag] = it.displayName() }
                    }
                    RequestDisplayMaps.fromRuntime(
                        routerSelectorTags = config.routerSelectorTags,
                        stableToName = emptyMap(),
                        tagToProfileName = tagToName,
                    )
                },
                runtimeGeneration = svc.data.applyGeneration,
                scope = runtimeScope,
            )
        }
        syncRequestObserver()
    }

    fun syncRequestObserver() {
        val enabled = service?.data?.binder?.requestObservers?.hasSubscribers() == true
        connectionObserver?.setEnabled(enabled)
    }

    override fun close() {
        try {
            connectionObserver?.stop()
            runBlocking { looper?.stop() }
        } finally {
            connectionObserver = null
            looper = null
            runtimeScope.cancel()
            super.close()
        }
    }
}
