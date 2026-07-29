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
        if (instance != state.activeInstance || state.activeProvider != "unifiedpush") return
        // Always show the native notification immediately from the push payload.
        // This eliminates the JS round-trip delay on the warm path (app alive in
        // background). JS still receives the push-message event for in-app badge
        // updates and notification enrichment (inbox grouping, fetched content
        // for event_id_only payloads). When JS calls sendNotification() with the
        // same notification ID, Android UPDATES the existing notification rather
        // than showing a duplicate. If no JS push-message listener is attached,
        // NotificationPlugin.onUnifiedPushMessage drops the event and the native
        // post stands alone.
        UnifiedPushNotifier.showFromPush(this, content)
        NotificationPlugin.instance?.onUnifiedPushMessage(content, instance)
    }
}
