package app.tauri.notification

import android.content.Context

object FcmBridge {
  fun useEmbeddedDelivery(context: Context, embedded: Boolean) = Unit

  fun isAvailable(context: Context): Boolean = false

  fun deleteToken(onResult: (FcmDeleteResult) -> Unit) {
    onResult(FcmDeleteResult.NotConfigured)
  }
}
