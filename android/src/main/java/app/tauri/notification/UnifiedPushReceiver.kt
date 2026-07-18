package app.tauri.notification

import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushNewEndpoint(endpoint.url)
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushRegistrationFailed(reason.name)
    }

    override fun onUnregistered(context: Context, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushUnregistered()
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val content = String(message.content, Charsets.UTF_8)
        val plugin = NotificationPlugin.instance
        if (plugin != null) {
            plugin.onUnifiedPushMessage(content)
        } else {
            UnifiedPushNotifier.showFromPush(context, content)
        }
    }
}
