package app.tauri.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class PushSubscriptions(context: Context) {
    private val prefs = context.getSharedPreferences("tauri-push-subscriptions", Context.MODE_PRIVATE)

    fun instances(): Set<String> = synchronized(LOCK) {
        prefs.getStringSet("registered", emptySet())!!.toSet()
    }

    fun contains(instance: String): Boolean = instance in instances()

    fun instance(userId: String?, deviceId: String?, vapid: String?): String = synchronized(LOCK) {
        val identity = JSONArray(listOf(userId, deviceId, vapid?.trimEnd('='))).toString()
        val entries = entries()
        entries.optString(identity).takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString().also {
            write(entries.put(identity, it))
        }
    }

    fun remember(instance: String) = synchronized(LOCK) {
        if (!contains(instance)) write(entries(), instances() + instance)
    }

    fun forget(instance: String) = synchronized(LOCK) {
        val entries = entries()
        entries.keys().asSequence().filter { entries.getString(it) == instance }.toList().forEach(entries::remove)
        write(entries, instances() - instance)
    }

    private fun entries(): JSONObject = JSONObject(prefs.getString("instances", "{}") ?: "{}")

    private fun write(entries: JSONObject, registered: Set<String> = instances()) {
        check(prefs.edit().putString("instances", entries.toString()).putStringSet("registered", registered).commit()) {
            "Could not save push subscriptions"
        }
    }

    companion object {
        private val LOCK = Any()
    }
}
