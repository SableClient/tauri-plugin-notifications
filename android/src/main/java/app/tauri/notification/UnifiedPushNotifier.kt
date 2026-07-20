package app.tauri.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

object UnifiedPushNotifier {
    private const val CHANNEL_ID = "messages"

    fun showFromPush(context: Context, rawMessage: String) {
        val rootJson = try {
            JSONObject(rawMessage)
        } catch (e: Exception) {
            null
        } ?: return

        val notification = rootJson.optJSONObject("notification") ?: return

        val roomId = notification.optString("room_id")
        val eventId = notification.optString("event_id")
        val sender = notification.optString("sender_display_name")
        val title = notification.optString("room_name").ifEmpty { "New message" }
        val body = buildBody(notification, sender)

        // Extract user_id from top-level or notification sub-object
        val userId = rootJson.optString("user_id").ifEmpty {
            notification.optString("user_id")
        }

        ensureChannel(context)

        val iconId = context.resources
            .getIdentifier("notification_icon", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        val notifId = (roomId.ifEmpty { eventId }).hashCode()

        // Build intent mimicking TauriNotificationManager.buildIntent() so
        // that NotificationPlugin.onIntent() -> handleNotificationActionPerformed()
        // -> extractLocalNotificationData() extracts room_id/event_id/user_id
        // from NOTIFICATION_OBJ_INTENT_KEY, matching the warm-path behavior.
        val intent = buildPushIntent(context, notifId, roomId, eventId, userId)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(context, notifId, intent, flags)
            )

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    /**
     * Builds an intent carrying the push payload so that
     * [NotificationPlugin.onIntent] can extract it via
     * [TauriNotificationManager.handleNotificationActionPerformed] and
     * [NotificationPlugin.extractLocalNotificationData].
     *
     * Mirrors the structure set by [TauriNotificationManager.buildIntent] in the
     * warm path (JS-triggered sendNotification). Because [UnifiedPushNotifier]
     * runs when [NotificationPlugin.instance] is null (cold start), we replicate
     * the intent extras rather than calling into the not-yet-initialized plugin.
     */
    private fun buildPushIntent(
        context: Context,
        notifId: Int,
        roomId: String,
        eventId: String,
        userId: String
    ): Intent {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)!!
        intent.action = Intent.ACTION_MAIN
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        intent.putExtra(NOTIFICATION_INTENT_KEY, notifId)
        intent.putExtra(ACTION_INTENT_KEY, DEFAULT_PRESS_ACTION)
        intent.putExtra(NOTIFICATION_IS_REMOVABLE_KEY, true)

        // Build the sourceJson that extractLocalNotificationData expects:
        // a JSON object with "id" and "extra" containing room context.
        val extraJson = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", eventId)
            if (userId.isNotEmpty()) put("user_id", userId)
        }
        val sourceJson = JSONObject().apply {
            put("id", notifId)
            put("extra", extraJson)
        }.toString()
        intent.putExtra(NOTIFICATION_OBJ_INTENT_KEY, sourceJson)

        return intent
    }

    private fun buildBody(notification: JSONObject, sender: String): String {
        val prefix = if (sender.isNotEmpty()) "$sender: " else ""
        if (notification.optString("type") == "m.room.encrypted") {
            return "${prefix}Encrypted message"
        }
        val text = notification.optJSONObject("content")?.optString("body")?.takeIf { it.isNotEmpty() }
        return if (text != null) "$prefix$text" else "${prefix}New message"
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Matrix message and invite notifications"
                }
            )
        }
    }
}
