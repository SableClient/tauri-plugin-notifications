package app.tauri.notification

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

object FcmBridge {
  fun isConfigured(context: Context): Boolean =
    try {
      FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Exception) {
      false
    }

  fun fetchToken(onResult: (FcmTokenResult) -> Unit) {
    try {
      FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        onResult(
          if (task.isSuccessful) FcmTokenResult.Success(task.result)
          else FcmTokenResult.Failed("Failed to get FCM token: ${task.exception?.message}")
        )
      }
    } catch (error: Exception) {
      onResult(FcmTokenResult.Unavailable(error.message ?: "Failed to get FCM token"))
    }
  }

  fun deleteToken(onResult: (FcmDeleteResult) -> Unit) {
    try {
      FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
        onResult(
          if (task.isSuccessful) FcmDeleteResult.Deleted
          else FcmDeleteResult.Failed("Failed to delete FCM token: ${task.exception?.message}")
        )
      }
    } catch (_: Exception) {
      onResult(FcmDeleteResult.NotConfigured)
    }
  }
}
