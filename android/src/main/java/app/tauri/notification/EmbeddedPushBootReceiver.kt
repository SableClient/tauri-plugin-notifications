package app.tauri.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the gateway websocket after a reboot; nothing else would. */
class EmbeddedPushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        EmbeddedPushAlarm.restartService(context)
    }
}
