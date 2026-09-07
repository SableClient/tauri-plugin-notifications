package app.tauri.notification

import android.content.Context
import org.json.JSONObject
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
  var p256dh: String?
    get() = prefs.getString("up-p256dh", null)
    set(value) = prefs.edit().putString("up-p256dh", value).apply()
  var auth: String?
    get() = prefs.getString("up-auth", null)
    set(value) = prefs.edit().putString("up-auth", value).apply()
  var distributor: String?
    get() = prefs.getString("up-distributor", null)
    set(value) = prefs.edit().putString("up-distributor", value).apply()
  var vapid: String?
    get() = prefs.getString("up-vapid", null)
    set(value) = prefs.edit().putString("up-vapid", value).apply()
  /**
   * True when the user picked the in-app websocket distributor. Distinct from having no
   * distributor: embedded-FCM also registers the app itself, so the package name alone
   * cannot tell the two apart.
   */
  var useEmbeddedDistributor: Boolean
    get() = prefs.getBoolean("up-use-embedded", false)
    set(value) = prefs.edit().putBoolean("up-use-embedded", value).apply()
  /** The account a cold push decrypts against; there is no session to ask when cold. */
  var pushUserId: String?
    get() = prefs.getString("up-user-id", null)
    set(value) = prefs.edit().putString("up-user-id", value).apply()
  var pushDeviceId: String?
    get() = prefs.getString("up-device-id", null)
    set(value) = prefs.edit().putString("up-device-id", value).apply()
  /** Kept so a restart reuses the same endpoint. */
  var embeddedTopic: String?
    get() = prefs.getString("up-embedded-topic", null)
    set(value) = prefs.edit().putString("up-embedded-topic", value).apply()

  fun prepareEmbeddedReplay(endpoint: String, freshTopic: Boolean) = synchronized(REPLAY_LOCK) {
    if (prefs.getString("up-replay-endpoint", null) != endpoint) {
      prefs.edit()
        .putString("up-replay-endpoint", endpoint)
        .putLong("up-replay-start", if (freshTopic) 0 else -1)
        .remove("up-replay-completed")
        .apply()
    }
  }

  fun initializeEmbeddedReplay(endpoint: String, serverTime: Long): Boolean = synchronized(REPLAY_LOCK) {
    if (this.endpoint != endpoint || serverTime <= 0) return@synchronized false
    val sameEndpoint = prefs.getString("up-replay-endpoint", null) == endpoint
    val start = if (sameEndpoint) prefs.getLong("up-replay-start", -1) else -1
    prefs.edit()
      .putString("up-replay-endpoint", endpoint)
      .putLong("up-replay-start", if (start >= 0) start else serverTime)
      .also { if (!sameEndpoint) it.remove("up-replay-completed") }
      .commit()
  }

  fun shouldProcessEmbeddedPush(endpoint: String, id: String, serverTime: Long): Boolean = synchronized(REPLAY_LOCK) {
    if (this.endpoint != endpoint || prefs.getString("up-replay-endpoint", null) != endpoint) {
      return@synchronized false
    }
    val start = prefs.getLong("up-replay-start", -1)
    start >= 0 && serverTime >= start && !completedEmbeddedPushes().has(id)
  }

  fun completeEmbeddedPush(endpoint: String, id: String): Boolean = synchronized(REPLAY_LOCK) {
    if (this.endpoint != endpoint || prefs.getString("up-replay-endpoint", null) != endpoint) {
      return@synchronized false
    }
    val completed = completedEmbeddedPushes()
    val now = System.currentTimeMillis()
    completed.keys().asSequence().toList().forEach { key ->
      if (completed.optLong(key) < now - REPLAY_RETENTION_MS) completed.remove(key)
    }
    completed.put(id, now)
    prefs.edit().putString("up-replay-completed", completed.toString()).commit()
  }

  private fun completedEmbeddedPushes(): JSONObject = try {
    JSONObject(prefs.getString("up-replay-completed", "{}") ?: "{}")
  } catch (_: Exception) {
    JSONObject()
  }

  fun clearRegistration() = synchronized(REPLAY_LOCK) {
    prefs.edit()
      .remove("push-instance")
      .remove("up-endpoint")
      .remove("up-p256dh")
      .remove("up-auth")
      .remove("up-distributor")
      .remove("up-vapid")
      .remove("up-embedded-topic")
      .remove("up-replay-endpoint")
      .remove("up-replay-start")
      .remove("up-replay-completed")
      .apply()
  }
  /** Mirrors the app's "show encrypted message content" setting. */
  var showEncryptedContent: Boolean
    get() = prefs.getBoolean("up-show-encrypted", false)
    set(value) { prefs.edit().putBoolean("up-show-encrypted", value).apply() }

  fun instanceForRegistration(): String {
    val current = activeInstance
    if (current != null && current != INSTANCE) {
      try { UnifiedPush.unregister(context, current, CachedKeyManager.getInstance(context)) } catch (_: Exception) {}
      activeInstance = INSTANCE
    }
    return INSTANCE
  }
  fun ensureExplicitInstance() {
    if (prefs.getString("push-instance", null) == null) {
      activeInstance = INSTANCE
    }
  }
  fun setUnifiedPushActive() { activeProvider = "unifiedpush" }

  companion object {
    const val INSTANCE = "default"
    private val REPLAY_LOCK = Any()
    private const val REPLAY_RETENTION_MS = 24 * 60 * 60 * 1000L
  }
}
