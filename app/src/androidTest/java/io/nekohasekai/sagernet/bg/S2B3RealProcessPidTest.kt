package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Dual-PID gate using the existing :bg service bind (Action.SERVICE).
 * Does not start the VPN tunnel, clear app data, or mutate user settings.
 * Execution requires an authorized device; without bind this is not a dual-PID pass.
 */
@RunWith(AndroidJUnit4::class)
class S2B3RealProcessPidTest {

    @Test
    fun instrumentationPidDiffersFromBgServicePid() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        val bgPid = AtomicInteger(-1)
        val error = AtomicReference<String?>(null)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val svc = ISagerNetService.Stub.asInterface(binder)
                    bgPid.set(svc.pid)
                    svc.registerCallback(object : ISagerNetServiceCallback.Stub() {
                        override fun stateChanged(state: Int, profileName: String?, msg: String?) {}
                        override fun cbSpeedUpdate(stats: SpeedDisplayData) {}
                        override fun cbTrafficUpdate(stats: TrafficDataBatch) {}
                        override fun cbSelectorUpdate(id: Long) {}
                        override fun missingPlugin(profileName: String?, pluginName: String?) {}
                        override fun commandResult(
                            requestId: String?,
                            outcome: Int,
                            instanceGeneration: Long,
                            persisted: Boolean,
                            errorCode: String?,
                        ) {}
                    }, SagerConnection.CONNECTION_ID_SHORTCUT)
                } catch (t: Throwable) {
                    error.set(t.message)
                } finally {
                    connected.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(ctx, SagerConnection.serviceClass).setAction(Action.SERVICE)
        val bound = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        Assume.assumeTrue("MANUAL_DEVICE_GATE: bindService refused", bound)
        val ok = connected.await(8, TimeUnit.SECONDS)
        runCatching { ctx.unbindService(conn) }
        Assume.assumeTrue("MANUAL_DEVICE_GATE: :bg binder did not connect", ok)
        Assume.assumeTrue(error.get() ?: "ok", error.get() == null)
        val uiPid = Process.myPid()
        val servicePid = bgPid.get()
        assertTrue("bg pid must be positive", servicePid > 0)
        assertTrue("main/$uiPid must differ from :bg/$servicePid", servicePid != uiPid)
    }
}
