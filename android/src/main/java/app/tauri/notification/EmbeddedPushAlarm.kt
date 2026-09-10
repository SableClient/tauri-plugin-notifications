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

    fun onAlarm(context: Context) {
        val state = UnifiedPushStateStore(context)
        if (state.activeProvider != "embedded") return
        if (EmbeddedPushEndpoint.webSocketUrlForEndpoint(state.endpoint) == null) return

        try {
            EmbeddedPushService.start(context)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restart the push service from an alarm: ${error.message}")
            PushDiagnostics.record(context, PushOutcome.EMBEDDED_SOCKET_FAILED)
        }
    }
}

internal class EmbeddedPushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EmbeddedPushAlarm.onAlarm(context)
    }
}
