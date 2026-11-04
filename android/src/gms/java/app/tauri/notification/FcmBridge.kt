package app.tauri.notification

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessaging

object FcmBridge {
  fun useEmbeddedDelivery(context: Context, embedded: Boolean) {
    // Both libraries claim C2DM delivery; only the selected transport may consume it.
    val receiver = ComponentName(context, "com.google.firebase.iid.FirebaseInstanceIdReceiver")
    val state = if (embedded) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
      else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    if (context.packageManager.getComponentEnabledSetting(receiver) != state) {
      context.packageManager.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
    }
  }

  fun isAvailable(context: Context): Boolean =
    runCatching { com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
      .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    }.getOrDefault(false)

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
