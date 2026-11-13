package app.tauri.notification

import android.content.Context

object FcmBridge {
  const val AUTOMATIC = false

  fun isAvailable(context: Context): Boolean =
    runCatching { context.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled }
      .getOrDefault(false)
}
