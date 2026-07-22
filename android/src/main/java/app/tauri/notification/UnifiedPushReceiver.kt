package app.tauri.notification

import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushNewEndpoint(
            endpoint.url,
            endpoint.pubKeySet?.pubKey,
            endpoint.pubKeySet?.auth,
            instance,
        )
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushRegistrationFailed(reason.name, instance)
    }

    override fun onUnregistered(context: Context, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushUnregistered(instance)
    }

    override fun onTempUnavailable(context: Context, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushTemporaryUnavailable(instance)
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val content = String(message.content, Charsets.UTF_8)
        val state = UnifiedPushStateStore(context)
        if (instance != state.activeInstance || state.activeProvider != "unifiedpush") return
        val plugin = NotificationPlugin.instance
        if (plugin != null) {
            plugin.onUnifiedPushMessage(content, instance)
        } else {
            UnifiedPushNotifier.showFromPush(context, content)
        }
    }
}
