package app.tauri.notification

import android.content.Context

class MatrixPushDelegate : PushDelegate {
    override fun isActivation(payload: String): Boolean =
        MatrixPushPayload.parse(payload)?.optString("ack_token")?.isNotEmpty() == true

    override fun render(context: Context, payload: String) =
        UnifiedPushNotifier.showFromPush(context, payload)

    override fun schedule(context: Context, payload: String) =
        PushRenderWorker.enqueue(context, payload)

    override fun endpointChanged(context: Context) = PushRegistrationWorker.enqueue(context)

    override fun cancelPending(context: Context) {
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("push-render")
    }
}
