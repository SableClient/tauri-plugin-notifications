package app.tauri.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.tauri.Logger
import com.fasterxml.jackson.databind.ObjectMapper
import org.json.JSONObject
import kotlin.math.abs

object UnifiedPushNotifier {
    // Kept in sync with the channel ids the app creates in UnifiedPushNotifications.ts.
    private const val MESSAGES_CHANNEL_ID = "messages.v2"
    private const val INVITES_CHANNEL_ID = "invites"
    private const val ACTION_TYPE_ID = "sable-message"
    private const val GROUP_KEY = "matrix_messages"

    fun showFromPush(context: Context, rawMessage: String) {
        val rootJson = try {
            JSONObject(rawMessage)
        } catch (e: Exception) {
            null
        } ?: return

        // Also accept the payload nested as a JSON string, not just an object.
        val notification = rootJson.optJSONObject("notification")
            ?: rootJson.optString("notification").takeIf { it.isNotEmpty() }?.let {
                try { JSONObject(it) } catch (e: Exception) { null }
            }
            ?: return

        val roomId = notification.optString("room_id")
        val eventId = notification.optString("event_id")
        val sender = notification.optString("sender_display_name")
        val isInvite = notification.optString("type") == "m.room.member" &&
            notification.optJSONObject("content")?.optString("membership") == "invite"
        val roomName = notification.optString("room_name")
        val title = if (isInvite) {
            "New Invitation"
        } else {
            roomName.ifEmpty { sender.ifEmpty { "New message" } }
        }
        val body = if (isInvite) buildInviteBody(sender, roomName) else buildBody(context, notification, sender)
        val channelId = if (isInvite) INVITES_CHANNEL_ID else MESSAGES_CHANNEL_ID

        val userId = rootJson.optString("user_id").ifEmpty {
            notification.optString("user_id")
        }

        ensureChannels(context)

        val iconId = context.resources
            .getIdentifier("notification_icon", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        // Notification identity must match the warm path so the JS side can
        // enrich or clear this entry: untagged Android key (null, id) with
        // id = Math.abs(hashCode(userId + '\u0000' + roomId)). Without a user
        // id in the payload the warm identity cannot be reproduced; fall
        // back to the room/event key (stable same-room updates, but no warm
        // clear/enrich match).
        val notifId = if (userId.isNotEmpty() && roomId.isNotEmpty()) {
            roomNotificationId(userId, roomId)
        } else {
            Logger.warn(
                Logger.tags(TAG),
                "Push payload has no user_id; cold notification will not match warm identity"
            )
            fallbackNotificationId(roomId.ifEmpty { eventId })
        }

        val intent = buildPushIntent(context, notifId, roomId, eventId, userId)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconId)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setContentIntent(
                PendingIntent.getActivity(context, notifId, intent, flags)
            )

        // Same style as the warm path, so JS enrichment updates it in place.
        if (isInvite) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        } else {
            builder.setStyle(
                NotificationCompat.MessagingStyle(
                    Person.Builder().setName(SELF_PERSON_NAME).build()
                ).addMessage(
                    messageText(context, notification),
                    System.currentTimeMillis(),
                    sender.takeIf { it.isNotEmpty() }?.let { Person.Builder().setName(it).build() }
                )
            )
        }

        if (!isInvite) {
            addReplyAction(context, builder, notifId, roomId, eventId, userId, flags)
        }

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    private fun addReplyAction(
        context: Context,
        builder: NotificationCompat.Builder,
        notifId: Int,
        roomId: String,
        eventId: String,
        userId: String,
        flags: Int
    ) {
        val storage = NotificationStorage(context, ObjectMapper())
        val actions = storage.getActionGroup(ACTION_TYPE_ID)
        for (action in actions) {
            if (action == null) continue
            val actionIntent = buildPushIntent(context, notifId, roomId, eventId, userId, action.id)
            val actionPendingIntent = PendingIntent.getActivity(
                context, notifId + action.id.hashCode(), actionIntent, flags
            )
            val actionBuilder = NotificationCompat.Action.Builder(
                R.drawable.ic_transparent, action.title, actionPendingIntent
            )
            if (action.input == true) {
                actionBuilder.addRemoteInput(
                    RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(action.title).build()
                )
            }
            builder.addAction(actionBuilder.build())
        }
    }

    /**
     * Stable, nonnegative notification id matching the warm-path (deployed
     * Sable JS) identity for a room:
     * `Math.abs(hashCode(userId + '\u0000' + roomId))`. The JS hash is a 32-bit
     * wrap-around hash over UTF-16 code units, exactly what
     * [String.hashCode] computes, and JS `Math.abs` corresponds to
     * [kotlin.math.abs] here. [Int.MIN_VALUE] has no positive counterpart
     * (the JS result 2^31 cannot cross the bridge as an Int), so it is
     * mapped safely to 0.
     */
    internal fun roomNotificationId(userId: String, roomId: String): Int {
        val hash = userId + '\u0000' + roomId
        return hash.hashCode().let { if (it == Int.MIN_VALUE) 0 else abs(it) }
    }

    /**
     * Stable, nonnegative id for a room-or-event key. Used only when the
     * push payload carries no user id; this identity deliberately differs
     * from the warm-path one.
     */
    internal fun fallbackNotificationId(roomOrEventKey: String): Int =
        roomOrEventKey.hashCode() and Int.MAX_VALUE

    /**
     * Builds an intent carrying the push payload so that
     * [NotificationPlugin.onIntent] can extract it via
     * [TauriNotificationManager.handleNotificationActionPerformed] and
     * [NotificationPlugin.extractLocalNotificationData].
     *
     * Mirrors the structure set by [TauriNotificationManager.buildIntent] in the
     * warm path (JS-triggered sendNotification).
     */
    private fun buildPushIntent(
        context: Context,
        notifId: Int,
        roomId: String,
        eventId: String,
        userId: String,
        action: String = DEFAULT_PRESS_ACTION
    ): Intent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        intent.action = Intent.ACTION_MAIN
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        intent.putExtra(NOTIFICATION_INTENT_KEY, notifId)
        intent.putExtra(ACTION_INTENT_KEY, action)
        intent.putExtra(NOTIFICATION_IS_REMOVABLE_KEY, true)

        val extraJson = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", eventId)
            if (userId.isNotEmpty()) put("user_id", userId)
            put("instance", UnifiedPushStateStore.INSTANCE)
        }
        val sourceJson = JSONObject().apply {
            put("id", notifId)
            put("extra", extraJson)
            put("actionTypeId", ACTION_TYPE_ID)
        }.toString()
        intent.putExtra(NOTIFICATION_OBJ_INTENT_KEY, sourceJson)

        return intent
    }

    /** The message text on its own, without a sender prefix. */
    private fun messageText(context: Context, notification: JSONObject): String {
        if (notification.optString("type") == "m.room.encrypted") {
            if (!UnifiedPushStateStore(context).showEncryptedContent) return "Encrypted message"
            return decryptedBody(context, notification) ?: "Encrypted message"
        }
        return notification.optJSONObject("content")
            ?.optString("body")
            ?.takeIf { it.isNotEmpty() }
            ?: "New message"
    }

    /** Rebuilds the encrypted event so the native engine can open it. */
    private fun decryptedBody(context: Context, notification: JSONObject): String? {
        val state = UnifiedPushStateStore(context)
        val userId = state.pushUserId ?: return null
        val deviceId = state.pushDeviceId ?: return null
        val roomId = notification.optString("room_id").takeIf { it.isNotEmpty() } ?: return null
        val content = notification.optJSONObject("content") ?: return null

        val event = JSONObject()
            .put("type", "m.room.encrypted")
            .put("room_id", roomId)
            .put("content", content)
            .put("event_id", notification.optString("event_id"))
            .put("sender", notification.optString("sender"))
            .put("origin_server_ts", System.currentTimeMillis())

        val clear = PushPayloadDecryptor.decrypt(
            context,
            userId,
            deviceId,
            roomId,
            event.toString(),
        ) ?: return null

        return try {
            JSONObject(clear).optJSONObject("content")?.optString("body")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildBody(context: Context, notification: JSONObject, sender: String): String {
        val prefix = if (sender.isNotEmpty()) "$sender: " else ""
        return "$prefix${messageText(context, notification)}"
    }

    private fun buildInviteBody(sender: String, roomName: String): String = when {
        sender.isNotEmpty() && roomName.isNotEmpty() -> "$sender invites you to $roomName"
        sender.isNotEmpty() -> "from $sender"
        roomName.isNotEmpty() -> "to $roomName"
        else -> ""
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(
            manager,
            MESSAGES_CHANNEL_ID,
            "Messages",
            "Matrix message notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        ensureChannel(
            manager,
            INVITES_CHANNEL_ID,
            "Invitations",
            "Room and space invitations",
            NotificationManager.IMPORTANCE_DEFAULT
        )
    }

    private fun ensureChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        channelDescription: String,
        importance: Int
    ) {
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply { description = channelDescription }
        )
    }
}
