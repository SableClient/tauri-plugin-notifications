package app.tauri.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

object UnifiedPushNotifier {
    private const val CHANNEL_ID = "messages"

    fun showFromPush(context: Context, rawMessage: String) {
        val notification = try {
            JSONObject(rawMessage).optJSONObject("notification")
        } catch (e: Exception) {
            null
        } ?: return

        val roomId = notification.optString("room_id")
        val sender = notification.optString("sender_display_name")
        val title = notification.optString("room_name").ifEmpty { "New message" }
        val body = buildBody(notification, sender)

        ensureChannel(context)

        val iconId = context.resources
            .getIdentifier("notification_icon", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    roomId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        val id = (roomId.ifEmpty { notification.optString("event_id") }).hashCode()
        NotificationManagerCompat.from(context).notify(id, builder.build())
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
