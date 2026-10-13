package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

internal fun shouldRetrySubscriptionUpdate(failed: Boolean, runAttemptCount: Int): Boolean =
    failed && runAttemptCount < 3

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription?.autoUpdate == true }
        if (subscriptions.isEmpty()) return

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay = subscriptions.minOfOrNull {
            (it.subscription?.autoUpdateDelay ?: 1440).toLong()
        } ?: 1440L
        if (minDelay < 15L) minDelay = 15L

        val now = System.currentTimeMillis() / 1000L
        val minRemainingSec = subscriptions.minOfOrNull { sub ->
            val last = (sub.subscription?.lastUpdated ?: 0).toLong()
            val delaySec = ((sub.subscription?.autoUpdateDelay ?: minDelay.toInt()).toLong()) * 60L
            maxOf(0L, (last + delaySec) - now)
        } ?: 0L
        val minInitDelay = minRemainingSec.coerceIn(0L, minDelay * 60L)

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                .apply {
                    if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                    if (subscriptions.any { it.subscription?.link?.startsWith("content://") != true }) {
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    }
                    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                }
                .build()
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription?.autoUpdate == true }
            if (!DataStore.serviceState.connected) {
                Logs.d({ "work: not connected" })
                subscriptions = subscriptions.filter { it.subscription?.updateWhenConnectedOnly != true }
            }

            val now = System.currentTimeMillis() / 1000L
            var failed = false
            try {
                for (profile in subscriptions) {
                    val subscription = profile.subscription ?: continue
                    val lastUpdated = (subscription.lastUpdated ?: 0).toLong()
                    val autoUpdateDelay = (subscription.autoUpdateDelay ?: 1440).toLong()

                    if ((now - lastUpdated) < autoUpdateDelay * 60L) {
                        Logs.d({ "work: not updating " + profile.displayName() })
                        continue
                    }
                    Logs.d({ "work: updating " + profile.displayName() })

                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    try {
                        nm.notify(2, notification.build())
                    } catch (_: SecurityException) {
                        // Denied notification permission must not prevent subscription updates.
                    }

                    if (!GroupUpdater.executeUpdate(profile, false)) failed = true
                }
            } finally {
                nm.cancel(2)
            }

            return if (shouldRetrySubscriptionUpdate(failed, runAttemptCount)) Result.retry()
                else if (failed) Result.failure() else Result.success()
        }
    }

}
