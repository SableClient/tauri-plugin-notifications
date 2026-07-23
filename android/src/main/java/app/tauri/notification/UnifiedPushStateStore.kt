package app.tauri.notification

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID

internal class UnifiedPushStateStore(private val context: Context) {
  private val prefs = context.getSharedPreferences("tauri-notifications", Context.MODE_PRIVATE)

  var activeProvider: String?
    get() = prefs.getString("push-provider", null)?.takeUnless { it == "none" }
      ?: if (!prefs.contains("push-provider") && UnifiedPush.getSavedDistributor(context) != null) "unifiedpush" else null
    set(value) = prefs.edit().putString("push-provider", value ?: "none").apply()
  var activeInstance: String?
    get() = prefs.getString("push-instance", null)
      ?: if (activeProvider == "unifiedpush") INSTANCE else null
    set(value) = prefs.edit().putString("push-instance", value ?: INSTANCE).apply()
  var endpoint: String?
    get() = prefs.getString("up-endpoint", null)
    set(value) = prefs.edit().putString("up-endpoint", value).apply()

  fun clearRegistration() {
    prefs.edit().remove("push-instance").remove("up-endpoint").apply()
  }
  fun instanceForRegistration(): String {
    activeInstance?.let { return it }
    if (activeProvider == "unifiedpush") return INSTANCE
    return UUID.randomUUID().toString().also { activeInstance = it }
  }
  fun ensureExplicitInstance() {
    if (prefs.getString("push-instance", null) == null) {
      activeInstance = UUID.randomUUID().toString()
    }
  }
  fun setUnifiedPushActive() { activeProvider = "unifiedpush" }

  companion object {
    const val INSTANCE = "default"
  }
}
