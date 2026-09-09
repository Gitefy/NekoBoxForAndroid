package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.SagerNet

class ProxyService : Service(), BaseService.Interface {
    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)

    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null

    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:proxy")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override fun onDestroy() {
        data.binder.close()
        super.onDestroy()
    }
}
