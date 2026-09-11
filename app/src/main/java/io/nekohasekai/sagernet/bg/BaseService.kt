package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.routerStableId
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.route.RouterRuntimeMode
import io.nekohasekai.sagernet.route.RouterSelection
import io.nekohasekai.sagernet.route.RouterSelectionPlan
import io.nekohasekai.sagernet.route.RouterSelectionRequest
import io.nekohasekai.sagernet.route.routerNodeKey
import io.nekohasekai.sagernet.utils.ConnectionResetDebouncer
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        @Volatile var state = State.Stopped
        @Volatile var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD, Action.APPLY, Action.CLOSE -> runOnDefaultDispatcher {
                    service.handleApplyIntent(intent)
                }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (SagerNet.power.isDeviceIdleMode) {
                        proxy?.box?.sleep()
                    } else {
                        proxy?.box?.wake()
                        if (DataStore.wakeResetConnections) {
                            ConnectionResetDebouncer.resetAllConnections()
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    ConnectionResetDebouncer.resetAllConnections(force = true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> runOnDefaultDispatcher { service.stopRunner() }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null
        @Volatile var urlTestRefreshJob: Job? = null
        @Volatile var applyGeneration: Long = 0L
        @Volatile var applyRequest: ApplyRequest? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
                callback?.let(callbackIdMap::remove)
            }
        }

        val callbackIdMap = ConcurrentHashMap<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getPid(): Int = android.os.Process.myPid()
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"
        override fun getCurrentUrlTestSelections(): LongArray =
            data?.takeIf { it.state == State.Connected }?.proxy?.currentUrlTestSelections()
                ?: longArrayOf()

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
            if (id == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                data?.proxy?.looper?.requestUpdate()
            }
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTestWithTarget(
                    data!!.proxy!!.box,
                    DataStore.connectionTestURL,
                    DataStore.connectionTestTimeout,
                    data!!.proxy!!.config.connectionTestTargetTag.orEmpty(),
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        fun commandResult(result: ApplyResult) = launch {
            broadcast {
                it.commandResult(
                    result.requestId,
                    result.outcome.ordinal,
                    result.instanceGeneration,
                    result.persisted,
                    result.errorCode,
                )
            }
        }

        fun finishApply(request: ApplyRequest, generation: Long, result: ApplyResult) {
            ApplyCoordinator.publish(request, generation, result)
            commandResult(result)
        }

        override fun close() {
            callbacks.kill()
            callbackIdMap.clear()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun parseApplyRequest(intent: Intent?, defaultKind: CommandKind = CommandKind.START): ApplyRequest {
            intent ?: return ApplyRequest(
                kind = defaultKind,
                targetProfileId = null,
                routerStableTag = null,
                routerMemberId = null,
            )
            val kind = runCatching {
                CommandKind.valueOf(intent.getStringExtra(Action.EXTRA_KIND) ?: defaultKind.name)
            }.getOrDefault(
                when (intent.action) {
                    Action.CLOSE -> CommandKind.STOP
                    Action.RELOAD -> CommandKind.RELOAD
                    else -> defaultKind
                }
            )
            val routerTag = intent.getStringExtra(Action.EXTRA_ROUTER_TAG)
            val routerProxyId = intent.getLongExtra(Action.EXTRA_ROUTER_PROXY_ID, 0L).takeIf { it > 0L }
            return if (intent.action == Action.RELOAD && intent.getStringExtra(Action.EXTRA_KIND) == null) {
                ApplyService.broadcastToRequest(
                    routerTag,
                    routerProxyId,
                    intent.getBooleanExtra(Action.EXTRA_FORCE_FULL_RELOAD, false),
                )
            } else {
                ApplyRequest(
                    requestId = intent.getStringExtra(Action.EXTRA_REQUEST_ID) ?: ApplyRequest.generateRequestId(),
                    kind = if (intent.action == Action.CLOSE && intent.getStringExtra(Action.EXTRA_KIND) == null) {
                        CommandKind.STOP
                    } else kind,
                    targetProfileId = intent.getLongExtra(Action.EXTRA_TARGET_PROFILE_ID, -1L).takeIf { it > 0L },
                    routerStableTag = routerTag,
                    routerMemberId = routerProxyId,
                    forceFullReload = intent.getBooleanExtra(Action.EXTRA_FORCE_FULL_RELOAD, false),
                )
            }
        }

        suspend fun handleApplyIntent(intent: Intent) {
            val request = parseApplyRequest(intent, CommandKind.RELOAD)
            val reused = intent.getLongExtra(Action.EXTRA_INSTANCE_GENERATION, -1L)
            val generation = if (reused > 0L) {
                if (!ApplyCoordinator.isCurrent(reused)) return
                reused
            } else {
                ApplyCoordinator.accept(request).first
            }
            data.applyGeneration = generation
            data.applyRequest = request
            val failed = ApplyService.validateCommitted(request, generation)
            if (failed != null) {
                if (ApplyCoordinator.isCurrent(generation)) data.binder.finishApply(request, generation, failed)
                return
            }
            if (!ApplyCoordinator.isCurrent(generation)) return
            when (request.kind) {
                CommandKind.START -> {
                    if (data.state == State.Stopped) startRunner(request, generation)
                    else completeApply(request, generation, CommandOutcome.APPLIED, true)
                }
                CommandKind.STOP -> stopRunner(
                    restart = false,
                    msg = null,
                    stopRequest = request,
                    stopGeneration = generation,
                )
                CommandKind.RELOAD -> executeReload(request, generation)
            }
        }

        private fun completeApply(
            request: ApplyRequest,
            generation: Long,
            outcome: CommandOutcome,
            persisted: Boolean,
            errorCode: String? = null,
        ) {
            if (!ApplyCoordinator.isCurrent(generation)) return
            data.binder.finishApply(
                request,
                generation,
                ApplyResult(request.requestId, outcome, generation, persisted, errorCode),
            )
        }

        fun reload(
            routerTag: String? = null,
            routerProxyId: Long? = null,
            forceFullReload: Boolean = false,
        ) {
            runOnDefaultDispatcher {
                handleApplyIntent(
                    android.content.Intent(Action.APPLY).apply {
                        putExtra(Action.EXTRA_KIND, CommandKind.RELOAD.name)
                        if (routerTag != null) putExtra(Action.EXTRA_ROUTER_TAG, routerTag)
                        if (routerProxyId != null) putExtra(Action.EXTRA_ROUTER_PROXY_ID, routerProxyId)
                        if (forceFullReload) putExtra(Action.EXTRA_FORCE_FULL_RELOAD, true)
                    },
                )
            }
        }

        private suspend fun executeReload(request: ApplyRequest, generation: Long) {
            val committed = DataStore.configurationStore.readCommittedSettingsSnapshotOffMain()
            val profileId = ApplyService.resolveCommittedProfileId(request, committed)
            if (profileId == null) {
                completeApply(request, generation, CommandOutcome.FAILED, false, ApplyErrorCodes.INVALID_TARGET)
                return
            }
            val routerReloadRequested = request.routerStableTag != null && request.routerMemberId != null
            if (routerReloadRequested && dbOffMain {
                    trySelectRouter(request.routerStableTag!!, request.routerMemberId!!)
                }
            ) {
                completeApply(request, generation, CommandOutcome.APPLIED, true)
                return
            }
            if (!request.forceFullReload && !routerReloadRequested && canReloadSelector(profileId)) {
                val ent = dbOffMain { SagerDatabase.proxyDao.getById(profileId) }
                val tag = data.proxy?.config?.profileTagMap?.get(ent?.id) ?: ""
                if (tag.isNotBlank() && ent != null && data.proxy?.box != null) {
                    data.proxy!!.box.selectOutbound(tag)
                    completeApply(request, generation, CommandOutcome.APPLIED, true)
                    return
                }
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner(request, generation)
                s.canStop -> stopRunner(true, null, request, generation)
                else -> {
                    Logs.w({ "Illegal state $s when invoking reload" })
                    completeApply(request, generation, CommandOutcome.FAILED, false, ApplyErrorCodes.CORE_FAILED)
                }
            }
        }

        private fun trySelectRouter(routerTag: String, proxyId: Long): Boolean {
            val runningProxy = data.proxy ?: return false
            if (routerTag.isBlank()) return false
            val router = SagerDatabase.routerGroupDao.getByStableTag(routerTag)
                ?.takeIf { it.enabled && it.stableTag.isNotBlank() && it.stableTag == routerTag }
                ?: return false
            if (runningProxy.config.routerSelectorTags[routerTag].isNullOrBlank()) return false
            val plan = RouterSelection.plan(
                request = RouterSelectionRequest(
                    routerTag = routerTag,
                    proxyId = proxyId,
                    mode = if (router.mode == RouterGroup.MODE_URL_TEST) {
                        RouterRuntimeMode.URL_TEST
                    } else {
                        RouterRuntimeMode.SELECTOR
                    },
                    routerEnabled = router.enabled,
                ),
                routerSelectorTags = runningProxy.config.routerSelectorTags,
                routerMemberIds = runningProxy.config.routerMemberIds,
                profileTags = runningProxy.config.profileTagMap,
                selectorGroupId = runningProxy.config.selectorGroupId,
            )
            if (plan !is RouterSelectionPlan.HotSwitch) return false
            if (!runningProxy.isInitialized() || !runningProxy.box.selectOutboundFor(plan.selectorTag, plan.targetTag)) {
                return false
            }
            // Runtime switch already succeeded. A DB miss must not fall through to full VPN reload.
            runCatching {
                val selected = SagerDatabase.proxyDao.getById(proxyId)
                    ?: error("proxy $proxyId missing after router hot-switch")
                SagerDatabase.routerGroupDao.updateSelection(
                    routerId = router.id,
                    selectedProxyId = proxyId,
                    selectedNodeKey = routerNodeKey(
                        selected.groupId,
                        selected.routerStableId(),
                    ),
                )
            }.onFailure { error ->
                Logs.w(
                    "Router hot-switch persisted selection failed for $routerTag; keeping runtime selection",
                    error,
                )
            }
            return true
        }

        fun canReloadSelector(): Boolean = canReloadSelector(
            ApplyService.resolveCommittedProfileId(
                ApplyRequest(kind = CommandKind.RELOAD, targetProfileId = null, routerStableTag = null, routerMemberId = null),
                DataStore.configurationStore.cachedAll(),
            ) ?: 0L
        )

        private fun canReloadSelector(profileId: Long): Boolean {
            val selectorGroupId = data.proxy?.config?.selectorGroupId ?: -1L
            if (selectorGroupId < 0L || profileId <= 0L) return false
            val ent = SagerDatabase.proxyDao.getById(profileId) ?: return false
            return ent.groupId == selectorGroupId
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner(request: ApplyRequest? = data.applyRequest, generation: Long? = data.applyGeneration) {
            this as Context
            val intent = Intent(this, javaClass)
            if (request != null) SagerNet.applyExtras(intent, request, generation)
            startForegroundService(intent)
        }

        fun killProcesses() {
            data.urlTestRefreshJob?.cancel()
            data.urlTestRefreshJob = null
            data.proxy?.close()
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkListener.stop(this)
            }
        }

        fun stopRunner(
            restart: Boolean = false,
            msg: String? = null,
            stopRequest: ApplyRequest? = null,
            stopGeneration: Long? = null,
        ) {
            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false

            if (data.state == State.Stopping) return
            data.notification?.destroy()
            data.notification = null
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // Close box / stop traffic looper off the main thread to avoid ANR on stop.
                withContext(Dispatchers.Default) {
                    killProcesses()
                }
                val data = data
                if (data.closeReceiverRegistered) {
                    unregisterReceiver(data.receiver)
                    data.closeReceiverRegistered = false
                }
                data.proxy = null

                // change the state
                data.changeState(State.Stopped, msg)
                if (restart) startRunner() else {
                    val req = stopRequest ?: data.applyRequest
                    val gen = stopGeneration ?: data.applyGeneration
                    if (req != null && req.kind == CommandKind.STOP && ApplyCoordinator.isCurrent(gen)) {
                        data.binder.finishApply(
                            req,
                            gen,
                            ApplyResult(req.requestId, CommandOutcome.STOPPED, gen, true, null),
                        )
                    }
                    stopSelf()
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            var previousNetwork: android.net.Network? = null
            var hadNetwork = false
            DefaultNetworkListener.start(this) { network ->
                if (network == null) {
                    previousNetwork = null
                    SagerNet.underlyingNetwork = null
                    return@start
                }
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    val networkChanged = hadNetwork && previousNetwork != network
                    previousNetwork = network
                    hadNetwork = true
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        upstreamInterfaceName = link.interfaceName
                    }
                    if (networkChanged || (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName)) {
                        Logs.d({ "Network changed: $oldName -> $upstreamInterfaceName" })
                        if (DataStore.networkChangeResetConnections) {
                            ConnectionResetDebouncer.resetAllConnections()
                        }
                        val runningProxy = data.proxy
                        if (data.state == State.Connected && runningProxy?.isInitialized() == true) {
                            // Debounce flapping networks so multi-router urltest bursts coalesce.
                            data.urlTestRefreshJob?.cancel()
                            data.urlTestRefreshJob = data.binder.launch(Dispatchers.IO) {
                                delay(URL_TEST_NETWORK_REFRESH_DEBOUNCE_MS)
                                if (data.state != State.Connected || data.proxy !== runningProxy) return@launch
                                if (!runningProxy.isInitialized()) return@launch
                                runningProxy.config.routerUrlTestTags.values.distinct().forEach {
                                    runningProxy.box.refreshURLTestFor(it)
                                }
                            }
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            this as Context
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.APPLY)
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                ContextCompat.registerReceiver(
                    this, data.receiver, filter, "$packageName.SERVICE", null,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                data.closeReceiverRegistered = true
            }

            val request = parseApplyRequest(intent, CommandKind.START)
            val reused = intent?.getLongExtra(Action.EXTRA_INSTANCE_GENERATION, -1L) ?: -1L
            val generation = if (reused > 0L) {
                if (!ApplyCoordinator.isCurrent(reused)) return Service.START_NOT_STICKY
                reused
            } else {
                ApplyCoordinator.accept(request).first
            }
            data.applyGeneration = generation
            data.applyRequest = request

            data.changeState(State.Connecting)
            data.connectingJob = data.binder.launch(start = CoroutineStart.LAZY) {
                try {
                    val startedAt = SystemClock.elapsedRealtime()
                    val placeholderNotification = onMainDispatcher {
                        createNotification(getString(R.string.app_name)).also { data.notification = it }
                    }
                    if (!placeholderNotification.show()) {
                        stopRunner(false, "${getString(R.string.service_failed)}foreground service")
                        return@launch
                    }
                    val failed = ApplyService.validateCommitted(request, generation)
                    if (failed != null) {
                        if (ApplyCoordinator.isCurrent(generation)) data.binder.finishApply(request, generation, failed)
                        stopRunner(false, getString(R.string.profile_empty))
                        return@launch
                    }
                    if (!ApplyCoordinator.isCurrent(generation)) return@launch
                    val profile = withContext(Dispatchers.IO) {
                        val committed = DataStore.configurationStore.readCommittedSettingsSnapshot()
                        val profileId = ApplyService.resolveCommittedProfileId(request, committed)
                            ?: return@withContext null
                        runCatching { SagerDatabase.proxyDao.getById(profileId) }.getOrNull()
                    }
                    if (profile == null) {
                        if (ApplyCoordinator.isCurrent(generation)) {
                            data.binder.finishApply(
                                request,
                                generation,
                                ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.INVALID_TARGET),
                            )
                        }
                        onMainDispatcher { stopRunner(false, getString(R.string.profile_empty)) }
                        return@launch
                    }
                    val (proxy, title) = withContext(Dispatchers.Default) {
                        val instance = ProxyInstance(profile, this@Interface)
                        instance to ServiceNotification.genTitle(profile)
                    }
                    data.proxy = proxy
                    onMainDispatcher {
                        data.notification?.let { runCatching { it.postNotificationTitle(title) } }
                    }
                    val notificationReadyAt = SystemClock.elapsedRealtime()

                    onDefaultDispatcher {
                        Executable.killAll()
                        preInit()
                        proxy.init()
                        DataStore.currentProfile = profile.id

                        proxy.processes = GuardedProcessPool {
                            Logs.w(it)
                            stopRunner(false, it.readableMessage)
                        }

                        startProcesses()
                    }
                    if (!ApplyCoordinator.isCurrent(generation) || data.state == State.Stopping) {
                        return@launch
                    }
                    data.changeState(State.Connected)
                    data.binder.finishApply(
                        request,
                        generation,
                        ApplyResult(request.requestId, CommandOutcome.APPLIED, generation, true, null),
                    )

                    lateInit()
                    Logs.i({
                        "$tag startup completed in ${SystemClock.elapsedRealtime() - startedAt} ms " +
                            "(notification=${notificationReadyAt - startedAt} ms)"
                    })
                } catch (_: CancellationException) {
                } catch (_: UnknownHostException) {
                    if (ApplyCoordinator.isCurrent(generation)) {
                        data.binder.finishApply(
                            request,
                            generation,
                            ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.CORE_FAILED),
                        )
                    }
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    onMainDispatcher {
                        Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    if (ApplyCoordinator.isCurrent(generation)) {
                        data.binder.finishApply(
                            request,
                            generation,
                            ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.CORE_FAILED),
                        )
                    }
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    if (ApplyCoordinator.isCurrent(generation)) {
                        data.binder.finishApply(
                            request,
                            generation,
                            ApplyResult(request.requestId, CommandOutcome.FAILED, generation, false, ApplyErrorCodes.CORE_FAILED),
                        )
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            data.connectingJob?.start()
            return Service.START_NOT_STICKY
        }
    }

    companion object {
        private const val URL_TEST_NETWORK_REFRESH_DEBOUNCE_MS = 1_500L
    }

}
