package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.StrictMode
import android.os.SystemClock
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RestoreCoordinator
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil
import java.util.concurrent.atomic.AtomicLong
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import io.nekohasekai.sagernet.bg.ApplyRequest
import io.nekohasekai.sagernet.bg.CommandKind
import io.nekohasekai.sagernet.bg.UserStartTarget
import androidx.work.Configuration as WorkConfiguration

class SagerNet : Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    private val nativeInterface = NativeInterface()

    val externalAssets: File by lazy { getExternalFilesDir(null) ?: filesDir }
    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        if (isMainProcess || isBgProcess) {
            externalAssets.mkdirs()
            Seq.setContext(this)
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                nativeInterface, nativeInterface, LocalResolverImpl
            )

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
                RestoreCoordinator.recoverOnBoot(
                    filesDir,
                    { DataStore.configurationStore.restore(it).success },
                    { RestoreCoordinator.installSagerExport(it) },
                )
            }
        }

        if (isMainProcess) {
            Theme.apply(this)
            Theme.applyNightTheme()
            AppLocale.apply()
            runOnDefaultDispatcher {
                DataStore.awaitReady()
                onMainDispatcher {
                    Theme.applyCommittedAppearance(this@SagerNet)
                }
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                }

                updateNotificationChannels()
            }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN fires often; FreeOSMemory can spike CPU without proven battery wins.
        // Cooldown keeps the safety valve without thrashing on every background transition.
        val now = SystemClock.elapsedRealtime()
        val previous = lastForceGcElapsedRealtime.get()
        if (now - previous < FORCE_GC_COOLDOWN_MS) return
        if (!lastForceGcElapsedRealtime.compareAndSet(previous, now)) return
        Libcore.forceGc()
    }

    @SuppressLint("InlinedApi")
    companion object {

        private const val FORCE_GC_COOLDOWN_MS = 60_000L
        private val lastForceGcElapsedRealtime = AtomicLong(0L)

        lateinit var application: SagerNet

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    PendingIntent.FLAG_IMMUTABLE
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            val vpnNotificationPolicy = ServiceNotification.vpnNotificationChannelPolicy()
            notification.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        ServiceNotification.vpnNotificationChannel,
                        application.getText(R.string.service_vpn),
                        vpnNotificationPolicy.importance
                    ).apply {
                        setLockscreenVisibility(vpnNotificationPolicy.lockscreenVisibility)
                    },   // #1355
                    NotificationChannel(
                        "service-proxy",
                        application.getText(R.string.service_proxy),
                        NotificationManager.IMPORTANCE_LOW
                    ), NotificationChannel(
                        "service-subscription",
                        application.getText(R.string.service_subscription),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ), NotificationChannel(
                        "connection-test",
                        application.getText(R.string.connection_test),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            )
        }

        fun applyExtras(intent: Intent, request: ApplyRequest, generation: Long? = null): Intent {
            intent.putExtra(Action.EXTRA_REQUEST_ID, request.requestId)
            intent.putExtra(Action.EXTRA_KIND, request.kind.name)
            intent.putExtra(Action.EXTRA_TARGET_PROFILE_ID, request.targetProfileId ?: -1L)
            if (request.routerStableTag != null) {
                intent.putExtra(Action.EXTRA_ROUTER_TAG, request.routerStableTag)
            }
            if (request.routerMemberId != null) {
                intent.putExtra(Action.EXTRA_ROUTER_PROXY_ID, request.routerMemberId)
            }
            if (request.forceFullReload) {
                intent.putExtra(Action.EXTRA_FORCE_FULL_RELOAD, true)
            }
            if (generation != null) {
                intent.putExtra(Action.EXTRA_INSTANCE_GENERATION, generation)
            }
            return intent
        }

        fun startService(targetProfileId: Long? = null) = startServiceViaApply(
            UserStartTarget.toStartRequest(targetProfileId),
        )

        fun startServiceViaApply(request: ApplyRequest) {
            ContextCompat.startForegroundService(
                application,
                applyExtras(Intent(application, SagerConnection.serviceClass), request),
            )
        }

        fun reloadService(routerTag: String? = null, routerProxyId: Long? = null) {
            val request = ApplyRequest(
                kind = CommandKind.RELOAD,
                targetProfileId = routerProxyId,
                routerStableTag = routerTag,
                routerMemberId = routerProxyId?.let { if (it > 0L) it else null },
            )
            application.sendBroadcast(applyExtras(Intent(Action.APPLY).setPackage(application.packageName), request))
            if (!DataStore.serviceState.started) {
                ContextCompat.startForegroundService(
                    application,
                    applyExtras(Intent(application, SagerConnection.serviceClass), request),
                )
            }
        }

        fun reloadServiceFully() {
            val request = ApplyRequest(
                kind = CommandKind.RELOAD,
                targetProfileId = null,
                routerStableTag = null,
                routerMemberId = null,
                forceFullReload = true,
            )
            application.sendBroadcast(applyExtras(Intent(Action.APPLY).setPackage(application.packageName), request))
            if (!DataStore.serviceState.started) {
                ContextCompat.startForegroundService(
                    application,
                    applyExtras(Intent(application, SagerConnection.serviceClass), request),
                )
            }
        }

        fun stopService() {
            val request = ApplyRequest(
                kind = CommandKind.STOP,
                targetProfileId = null,
                routerStableTag = null,
                routerMemberId = null,
            )
            application.sendBroadcast(applyExtras(Intent(Action.APPLY).setPackage(application.packageName), request))
        }

        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

}
