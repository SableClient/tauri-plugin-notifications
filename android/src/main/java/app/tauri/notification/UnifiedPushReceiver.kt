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
            if (state.activeInstance == instance) {
                state.endpoint = endpoint.url
                state.p256dh = endpoint.pubKeySet?.pubKey
                state.auth = endpoint.pubKeySet?.auth
            }
            PushDelegates.get(this).endpointChanged(this, instance, endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
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
        val state = UnifiedPushStateStore(this)
        if (!state.acceptsUnifiedPush(instance)) return
        PushSubscriptions(this).forget(instance)
        if (instance == (state.activeInstance ?: UnifiedPushStateStore.INSTANCE)) {
            state.clearRegistration(clearProvider = true)
        }
        NotificationPlugin.instance?.onUnifiedPushUnregistered(instance)
    }

    override fun onTempUnavailable(instance: String) {
        NotificationPlugin.instance?.onUnifiedPushTemporaryUnavailable(instance)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val state = UnifiedPushStateStore(this)
        if (!state.acceptsUnifiedPush(instance)) return
        // The connector falls back to the raw body when decryption fails. With keys
        // published, anyone who knows the endpoint could otherwise inject a notification.
        // A registration without VAPID is direct: the connector still makes keys, but the
        // server never gets them and pushes plain text.
        val direct = PushSubscriptions(this).registeredWithoutVapid(instance)
            ?: (instance == state.activeInstance && state.vapid == null)
        if (!message.decrypted && state.p256dh != null && !direct) return
        val content = String(message.content, Charsets.UTF_8)
        // The delegate owns cold rendering; JS retires alerts for active/read rooms.
        PushDelegates.get(this).schedule(this, content)
        NotificationPlugin.instance?.onUnifiedPushMessage(content, instance)
    }
}
