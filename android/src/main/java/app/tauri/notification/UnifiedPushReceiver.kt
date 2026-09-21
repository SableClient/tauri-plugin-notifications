package app.tauri.notification

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush entry point. Declared in the manifest as a non-exported
 * [PushService] with an intent-filter for [PushService.ACTION_PUSH_EVENT];
 * the connector library's own MessagingReceiverImpl receives the distributor
 * broadcasts and forwards them to this service over a bound connection.
 * Do NOT declare a BroadcastReceiver for the connector actions
 * (NEW_ENDPOINT/MESSAGE/UNREGISTERED/REGISTRATION_FAILED/TEMP_UNAVAILABLE):
 * it would shadow the library's MessagingReceiverImpl.
 */
class UnifiedPushReceiver : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (NotificationPlugin.instance == null) {
            val state = UnifiedPushStateStore(this)
            if (!state.acceptsUnifiedPush(instance)) return
            state.endpoint = endpoint.url
            state.p256dh = endpoint.pubKeySet?.pubKey
            state.auth = endpoint.pubKeySet?.auth
            PushRegistrationWorker.enqueue(this)
        }
        NotificationPlugin.instance?.onUnifiedPushNewEndpoint(
            endpoint.url,
            endpoint.pubKeySet?.pubKey,
            endpoint.pubKeySet?.auth,
            instance,
        )
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        NotificationPlugin.instance?.onUnifiedPushRegistrationFailed(reason.name, instance)
    }

    override fun onUnregistered(instance: String) {
        NotificationPlugin.instance?.onUnifiedPushUnregistered(instance)
    }

    override fun onTempUnavailable(instance: String) {
        NotificationPlugin.instance?.onUnifiedPushTemporaryUnavailable(instance)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val content = String(message.content, Charsets.UTF_8)
        val state = UnifiedPushStateStore(this)
        if (!state.acceptsUnifiedPush(instance)) return
        val validation = runCatching { org.json.JSONObject(content) }.getOrNull()
        if (validation?.has("ack_token") == true && validation.has("app_id") && !validation.has("notification")) {
            PushRenderWorker.enqueue(this, content)
            NotificationPlugin.instance?.onUnifiedPushMessage(content, instance)
            return
        }
        // WorkManager owns cold rendering; JS retires alerts for active/read rooms.
        PushRenderWorker.enqueue(this, content)
        NotificationPlugin.instance?.onUnifiedPushMessage(content, instance)
    }
}
