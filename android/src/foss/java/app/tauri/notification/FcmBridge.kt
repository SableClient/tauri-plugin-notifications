package app.tauri.notification

import android.content.Context

object FcmBridge {
  fun isConfigured(context: Context): Boolean = false

  fun fetchToken(onResult: (FcmTokenResult) -> Unit) {
    onResult(FcmTokenResult.Unavailable("FCM is not available in this build"))
  }

  fun deleteToken(onResult: (FcmDeleteResult) -> Unit) {
    onResult(FcmDeleteResult.NotConfigured)
  }
}
