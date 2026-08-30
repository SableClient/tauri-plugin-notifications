package app.tauri.notification

import android.content.Context

internal enum class PushOutcome {
    DECRYPTED,
    PLAINTEXT,
    HIDDEN_BY_SETTING,
    NO_ACCOUNT,
    NO_CONTENT,
    NO_NATIVE_LIB,
    DECRYPT_FAILED,
    EMPTY_BODY,
}

internal data class PushDiagnosticsSnapshot(
    val counts: Map<String, Int>,
    val lastOutcome: String?,
    val lastAt: Long,
)

internal object PushDiagnostics {
    private const val PREFS = "tauri-notifications"
    private const val KEY_PREFIX = "push-outcome-"
    private const val KEY_LAST = "push-outcome-last"
    private const val KEY_LAST_AT = "push-outcome-last-at"

    fun record(context: Context, outcome: PushOutcome) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + outcome.name
        prefs.edit()
            .putInt(key, prefs.getInt(key, 0) + 1)
            .putString(KEY_LAST, outcome.name)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
    }

    fun drain(context: Context): PushDiagnosticsSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = PushOutcome.entries.associate { outcome ->
            outcome.name to prefs.getInt(KEY_PREFIX + outcome.name, 0)
        }.filterValues { it > 0 }

        val snapshot = PushDiagnosticsSnapshot(
            counts = counts,
            lastOutcome = prefs.getString(KEY_LAST, null),
            lastAt = prefs.getLong(KEY_LAST_AT, 0L),
        )

        prefs.edit().apply {
            PushOutcome.entries.forEach { remove(KEY_PREFIX + it.name) }
            remove(KEY_LAST)
            remove(KEY_LAST_AT)
        }.apply()

        return snapshot
    }
}
