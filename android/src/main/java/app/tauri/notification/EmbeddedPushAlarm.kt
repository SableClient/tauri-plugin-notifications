package app.tauri.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.AlarmManagerCompat

internal object EmbeddedPushAlarm {
    private const val TAG = "EmbeddedPushAlarm"
    private const val REQUEST_CODE = 0x5AB1F
    private const val RETRY_BASE_MS = 60_000L
    private const val RETRY_MAX_MS = 900_000L
    private const val MAX_RETRY_SHIFT = 4

    @Volatile private var failedStarts = 0

    fun schedule(context: Context, delayMs: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + delayMs

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                manager,
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(context),
            )
            return
        }

        manager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, EmbeddedPushAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun onAlarm(context: Context) = restartService(context)

    /** A refused start is retried with backoff, since the alarm that triggered it is spent. */
    fun restartService(context: Context) {
        val state = UnifiedPushStateStore(context)
        if (!state.isEmbeddedActive) return
        if (EmbeddedPushEndpoint.webSocketUrlForEndpoint(state.endpoint) == null) return

        if (EmbeddedPushService.poke() || EmbeddedPushService.start(context)) {
            failedStarts = 0
            return
        }

        PushDelegates.get(context).record(context, "EMBEDDED_SOCKET_FAILED")
        val delay = (RETRY_BASE_MS shl failedStarts).coerceAtMost(RETRY_MAX_MS)
        if (failedStarts < MAX_RETRY_SHIFT) failedStarts++
        Log.w(TAG, "Retrying the push service start in ${delay}ms")
        schedule(context, delay)
    }
}

internal class EmbeddedPushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EmbeddedPushAlarm.onAlarm(context)
    }
}
