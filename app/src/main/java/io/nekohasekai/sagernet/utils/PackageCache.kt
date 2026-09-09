package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    val uidMap = HashMap<Int, HashSet<String>>()
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // Consecutive install/uninstall broadcasts (batch installs, staged updates)
    // used to each trigger a full PackageManager scan. Coalesce them: only the
    // last event in the window performs the heavy pass; intermediate events
    // only refresh the cheap uid map used by the connection-owner hot path.
    private const val RELOAD_DEBOUNCE_MS = 2_000L
    private val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reloadJob: Job? = null
    private val lastHeavyReloadMs = AtomicLong(0L)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            scheduleReload()
        }
        loaded.unlock()
    }

    /** Broadcast path: cheap uid refresh now, heavy pass debounced. */
    fun scheduleReload() {
        reloadUidMapOnly()
        synchronized(this) {
            reloadJob?.cancel()
            reloadJob = reloadScope.launch {
                delay(RELOAD_DEBOUNCE_MS)
                reloadHeavy()
            }
        }
    }

    /**
     * Full refresh. Direct callers are foreground UI (AppManager/AppList) that
     * explicitly need fresh labels/permissions, so they bypass the debounce.
     */
    @SuppressLint("InlinedApi")
    fun reload() {
        synchronized(this) {
            reloadJob?.cancel()
            reloadJob = null
        }
        lastHeavyReloadMs.set(android.os.SystemClock.elapsedRealtime())
        reloadInternal()
    }

    /** Heavy pass shared by [reload] and the debounced broadcast path. */
    private fun reloadHeavy() {
        lastHeavyReloadMs.set(android.os.SystemClock.elapsedRealtime())
        reloadInternal()
    }

    @SuppressLint("InlinedApi")
    private fun reloadInternal() {
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }

        installedPluginPackages = rawPackageInfo.filter {
            Plugins.isExe(it)
        }.associateBy { it.packageName }

        reloadUidMapOnly()
        labelMap.clear()
    }

    /**
     * Lightweight pass: single getInstalledApplications(0) without metadata.
     * Keeps findConnectionOwner correct between debounced heavy reloads and on
     * its own is sufficient for the uid->package hot path.
     */
    fun reloadUidMapOnly() {
        val installed = runCatching {
            app.packageManager.getInstalledApplications(0)
        }.getOrNull() ?: return
        installedApps = installed.associateBy { it.packageName }
        packageMap = installed.associate { it.packageName to it.uid }
        uidMap.clear()
        for (info in installed) {
            val uid = info.uid
            uidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelMap = mutableMapOf<String, String>()
    fun loadLabel(packageName: String): String {
        var label = labelMap[packageName]
        if (label != null) return label
        val info = installedApps[packageName] ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

}