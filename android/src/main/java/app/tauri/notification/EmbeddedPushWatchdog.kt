package app.tauri.notification

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Brings the push service back after the system killed it, without needing the exact-alarm permission. */
internal class EmbeddedPushWatchdog(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!UnifiedPushStateStore(applicationContext).isEmbeddedActive) {
            cancel(applicationContext)
        } else {
            EmbeddedPushAlarm.restartService(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "embedded-push-watchdog"
        private const val INTERVAL_MINUTES = 15L

        fun ensure(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EmbeddedPushWatchdog>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
