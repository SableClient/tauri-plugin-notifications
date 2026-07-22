package app.tauri.notification

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

internal class UnifiedPushStateStore(private val context: Context) {
  private val prefs = context.getSharedPreferences("tauri-notifications", Context.MODE_PRIVATE)

  var activeProvider: String?
    get() = prefs.getString("push-provider", null)?.takeUnless { it == "none" }
      ?: if (!prefs.contains("push-provider") && UnifiedPush.getSavedDistributor(context) != null) "unifiedpush" else null
    set(value) = prefs.edit().putString("push-provider", value ?: "none").apply()
  var activeInstance: String?
    get() = prefs.getString("push-instance", null) ?: INSTANCE
    set(value) = prefs.edit().putString("push-instance", value ?: INSTANCE).apply()
  fun setUnifiedPushActive() { activeProvider = "unifiedpush" }

  companion object {
    const val INSTANCE = "default"
  }
}
