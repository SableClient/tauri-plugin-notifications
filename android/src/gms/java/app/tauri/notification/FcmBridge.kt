package app.tauri.notification

import android.content.Context

object FcmBridge {
  fun isAvailable(context: Context): Boolean =
    runCatching { com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
      .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    }.getOrDefault(false)
}
