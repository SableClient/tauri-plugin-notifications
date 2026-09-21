package app.tauri.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val state = UnifiedPushStateStore(applicationContext)
        if (!state.notificationsEnabled || !state.acceptsUnifiedPush(state.activeInstance ?: "")) return Result.success()
        val user = state.pushUserId ?: return Result.failure()
        val device = state.pushDeviceId ?: return Result.failure()
        val endpoint = state.endpoint ?: return Result.failure()
        val operation = JSONObject().put("operation", "rotate")
            .put("user_id", user).put("device_id", device).put("endpoint", endpoint)
            .put("p256dh", state.p256dh).put("auth", state.auth)
        return if (PushPayloadDecryptor.maintain(applicationContext, operation.toString())) Result.success()
        else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("push-registration", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
