package app.tauri.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the gateway websocket after a reboot. Nothing else would: the service only
 * starts from a registration, and the app may not be opened for days.
 */
class EmbeddedPushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val state = UnifiedPushStateStore(context)
        if (state.distributor != NotificationPlugin.EMBEDDED_DISTRIBUTOR) return
        if (EmbeddedPushEndpoint.webSocketUrlForEndpoint(state.endpoint) == null) return

        EmbeddedPushService.start(context)
    }
}
