package app.tauri.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.tauri.plugin.JSObject
import org.json.JSONArray
import org.json.JSONException

private const val QUEUE_STORE_ID = "NotificationBackgroundActions"
private const val QUEUE_KEY = "actions"
private const val MAX_QUEUED_ACTIONS = 32

internal object BackgroundActions {
  fun runsInBackground(action: NotificationAction): Boolean =
    action.input != true && action.foreground == false

  fun pendingIntent(
    context: Context,
    activityIntent: Intent,
    requestCode: Int,
    flags: Int
  ): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).putExtras(activityIntent)
    return PendingIntent.getBroadcast(context, requestCode, intent, flags)
  }

  fun payload(intent: Intent): JSObject {
    val data = JSObject()
    data.put("inputValue", null)
    data.put("actionId", intent.getStringExtra(ACTION_INTENT_KEY))
    val source = intent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)
    val request = try {
      source?.let { JSObject(it) }
    } catch (e: JSONException) {
      null
    }
    data.put("notification", request)
    return data
  }

  @Synchronized
  fun queue(context: Context, data: JSObject) {
    val store = context.getSharedPreferences(QUEUE_STORE_ID, Context.MODE_PRIVATE)
    val queued = readQueue(store.getString(QUEUE_KEY, null))
    queued.put(data)
    val kept = JSONArray()
    for (index in maxOf(0, queued.length() - MAX_QUEUED_ACTIONS) until queued.length()) {
      kept.put(queued.get(index))
    }
    store.edit().putString(QUEUE_KEY, kept.toString()).commit()
  }

  @Synchronized
  fun drain(context: Context): List<JSObject> {
    val store = context.getSharedPreferences(QUEUE_STORE_ID, Context.MODE_PRIVATE)
    val queued = readQueue(store.getString(QUEUE_KEY, null))
    if (queued.length() == 0) return emptyList()
    store.edit().remove(QUEUE_KEY).commit()
    return (0 until queued.length()).mapNotNull { index ->
      queued.optJSONObject(index)?.let { JSObject(it.toString()) }
    }
  }

  private fun readQueue(raw: String?): JSONArray =
    try {
      if (raw == null) JSONArray() else JSONArray(raw)
    } catch (e: JSONException) {
      JSONArray()
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val notificationId = intent.getIntExtra(NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
    if (notificationId == Int.MIN_VALUE) return

    NotificationManagerCompat.from(context).cancel(notificationId)
    val data = BackgroundActions.payload(intent)
    val plugin = NotificationPlugin.instance
    if (plugin != null) plugin.triggerActionPerformed(data) else BackgroundActions.queue(context, data)
  }
}
