package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.broadcastReceiver

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null
    private val capturedTarget: Long?
        get() = intent.getLongExtra(Action.EXTRA_TARGET_PROFILE_ID, -1L).takeIf { it > 0L }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> connect.launch(capturedTarget) }
            registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                Context.RECEIVER_EXPORTED
            )
        } else connect.launch(capturedTarget)
    }

    private val connect = registerForActivityResult(StartService()) {
        if (it) Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class StartService : ActivityResultContract<Long?, Boolean>() {
        private var cachedIntent: Intent? = null
        var pendingTarget: Long? = null
            private set

        fun captureInput(input: Long?): Long? {
            pendingTarget = input?.takeIf { it > 0L }
            return pendingTarget
        }

        override fun getSynchronousResult(
            context: Context,
            input: Long?,
        ): SynchronousResult<Boolean>? {
            captureInput(input)
            if (DataStore.serviceMode == Key.MODE_VPN) VpnService.prepare(context)?.let { intent ->
                cachedIntent = intent
                return null
            }
            SagerNet.startService(pendingTarget)
            return SynchronousResult(false)
        }

        override fun createIntent(context: Context, input: Long?) =
            cachedIntent!!.also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?) =
            if (resultCode == Activity.RESULT_OK) {
                SagerNet.startService(pendingTarget)
                false
            } else {
                Logs.e("Failed to start VpnService: $intent")
                true
            }
    }
}
