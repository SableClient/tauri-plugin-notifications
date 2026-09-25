package app.tauri.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import java.util.UUID
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
    private const val CALLS_CHANNEL_ID = "calls"
    private const val ACTION_TYPE_ID = "sable-message"
    private const val CALL_ACTION_TYPE_ID = "sable-call"
    private const val GROUP_KEY = "matrix_messages"

    private const val RTC_NOTIFICATION_TYPE = "m.rtc.notification"
    private const val RTC_NOTIFICATION_TYPE_UNSTABLE = "org.matrix.msc4075.rtc.notification"
    private const val ANSWER_ACTION = "sable-call-answer"
    private const val DECLINE_ACTION = "sable-call-decline"
    private const val DEFAULT_RING_LIFETIME_MS = 30_000L
    private const val MAX_RING_LIFETIME_MS = 120_000L

    fun showFromPushInBackground(context: Context, rawMessage: String) {
        PushRenderWorker.enqueue(context, rawMessage)
    }

    fun showFromPush(context: Context, rawMessage: String, queuedRevision: String? = null) {
        if (!UnifiedPushStateStore(context).notificationsEnabled) {
            PushDiagnostics.record(context, PushOutcome.DISABLED)
            return
        }
        val notification = MatrixPushPayload.parse(rawMessage) ?: run {
            PushDiagnostics.record(context, PushOutcome.INVALID_PAYLOAD)
            return
        }
        val roomId = notification.optString("room_id")
        val userId = notification.optString("user_id")
        val store = UnifiedPushStateStore(context)
        val deviceId = store.deviceIdFor(userId)
        if (roomId.isEmpty()) {
            if (deviceId != null && notification.optJSONObject("counts")?.optInt("unread", -1) == 0) {
                PushDiagnostics.record(context, PushOutcome.ACCOUNT_READ_DISMISSED)
                dismissAccount(context, userId)
                return
            }
            PushDiagnostics.record(context, PushOutcome.MISSING_ROOM)
            return
        }
        if (store.knowsAnyAccount() && deviceId == null) {
            PushDiagnostics.record(context, if (userId.isEmpty()) PushOutcome.MISSING_RECIPIENT else PushOutcome.WRONG_RECIPIENT)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = if (userId.isNotEmpty()) roomNotificationId(userId, roomId) else fallbackNotificationId(roomId)
        val revision = queuedRevision ?: PushNotificationGate.revision(context, id)
        if (notification.optJSONObject("counts")?.optInt("unread", -1) == 0) {
            PushDiagnostics.record(context, PushOutcome.READ_DISMISSED)
            PushNotificationGate.dismiss(context, id) { manager.cancel(id) }
            return
        }
        val eventId = notification.optString("event_id")
        if (synchronized(PushNotificationGate) { NotificationReceipts.shouldDrop(context, id, eventId) }) {
            PushDiagnostics.record(context, PushOutcome.REPLAY_DROPPED)
            return
        }
        val generation = manager.activeNotifications.firstOrNull { it.id == id && it.tag == null }
            ?.notification?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            ?.messages?.firstOrNull { eventId.isNotEmpty() && it.extras.getString(EVENT_KEY) == eventId }
            ?.extras?.getString(GENERATION_KEY) ?: UUID.randomUUID().toString()
        val encrypted = notification.optString("type") == "m.room.encrypted"
        if (!encrypted) {
            if (isRing(notification)) {
                PushNotificationGate.post(context, id, revision) { postIncomingCall(context, notification, notification) }
                return
            }
            PushNotificationGate.post(context, id, revision) { post(context, notification, null, generation) }
            return
        }
        val event = encryptedEvent(notification)
        val local = if (deviceId != null && event != null) {
            PushPayloadDecryptor.decryptLocally(context, userId, deviceId, roomId, event)
        } else null
        when (local) {
            PushDecryptResult.Discard -> {
                PushDiagnostics.record(context, PushOutcome.DISCARDED)
                return
            }
            is PushDecryptResult.Success -> {
                PushDiagnostics.record(context, PushOutcome.DECRYPTED)
                val clear = runCatching { JSONObject(local.clearEventJson) }.getOrNull()
                if (clear == null) {
                    PushNotificationGate.post(context, id, revision) { post(context, notification, "Encrypted message", generation) }
                    return
                }
                showDecrypted(context, notification, clear, id, revision, generation, replacing = false)
                return
            }
            else -> {}
        }
        val quietly = local is PushDecryptResult.NeedsKey && local.quietly
        if (!quietly) {
            PushNotificationGate.post(context, id, revision) { post(context, notification, "Encrypted message", generation) }
            if (!store.showContent || !store.showEncryptedContent) {
                PushDiagnostics.record(context, PushOutcome.HIDDEN_BY_SETTING)
                return
            }
        }
        if (deviceId == null) return
        val (clear, outcome) = decryptedEvent(context, notification, userId, deviceId)
        PushDiagnostics.record(context, outcome)
        if (clear == null) return
        showDecrypted(context, notification, clear, id, revision, generation, replacing = !quietly)
    }

    private fun showDecrypted(
        context: Context,
        notification: JSONObject,
        clear: JSONObject,
        id: Int,
        revision: String,
        generation: String,
        replacing: Boolean,
    ) {
        val store = UnifiedPushStateStore(context)
        if (!store.notificationsEnabled) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (isRing(clear)) {
            PushNotificationGate.post(context, id, revision) {
                if (replacing) manager.cancel(id)
                postIncomingCall(context, notification, clear)
            }
            return
        }
        val allowed = store.showContent && store.showEncryptedContent
        val text = clear.optJSONObject("content")
            ?.optString("body")
            ?.takeIf { it.isNotEmpty() }
        if (text == null && allowed) PushDiagnostics.record(context, PushOutcome.EMPTY_BODY)
        val body = text?.takeIf { allowed }
        if (!replacing) {
            PushNotificationGate.post(context, id, revision) {
                post(context, notification, body ?: "Encrypted message", generation)
            }
            return
        }
        if (body == null) return
        PushNotificationGate.post(context, id, revision) {
            val stillCurrent = manager.activeNotifications.any {
                it.id == id && it.tag == null &&
                    NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)
                        ?.messages?.any { message -> message.extras.getString(GENERATION_KEY) == generation } == true
            }
            if (stillCurrent) post(context, notification, body, generation, silent = true)
        }
    }

    internal fun isRing(event: JSONObject): Boolean {
        val type = event.optString("type")
        if (type != RTC_NOTIFICATION_TYPE && type != RTC_NOTIFICATION_TYPE_UNSTABLE) return false
        return event.optJSONObject("content")?.optString("notification_type") == "ring"
    }

    private const val GENERATION_KEY = "sable.push.generation"
    private const val EVENT_KEY = ConversationHistory.EVENT_KEY
    private const val ENCRYPTED_KEY = ConversationHistory.ENCRYPTED_KEY
    private const val ACCOUNT_KEY = ConversationHistory.ACCOUNT_KEY

    private fun dismissAccount(context: Context, userId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.tag == null && it.notification.extras.getString(ACCOUNT_KEY) == userId }
            .forEach { active -> PushNotificationGate.dismiss(context, active.id) { manager.cancel(active.id) } }
    }

    private fun post(
        context: Context,
        notification: JSONObject,
        preview: String?,
        generation: String,
        silent: Boolean = false,
    ) {
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
        val text = if (isInvite) null else preview ?: messageText(context, notification)
        val body = if (isInvite) buildInviteBody(sender, roomName) else buildBody(sender, text.orEmpty())
        val channelId = if (isInvite) INVITES_CHANNEL_ID else MESSAGES_CHANNEL_ID

        val userId = notification.optString("user_id")

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

        if (NotificationReceipts.shouldDrop(context, notifId, eventId)) {
            PushDiagnostics.record(context, PushOutcome.REPLAY_DROPPED)
            return
        }
        var actionEventId = eventId

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val state = UnifiedPushStateStore(context)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconId)
            .addExtras(Bundle().apply {
                putString(GENERATION_KEY, generation)
                if (userId.isNotEmpty()) putString(ACCOUNT_KEY, userId)
            })
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent || state.notifyOnce)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)

        if (silent || !state.notificationSounds) builder.setSilent(true)

        // Same style as the warm path, so JS enrichment updates it in place.
        if (isInvite) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        } else {
            // The OS owns the history: swiping away an alert also discards its previews.
            val messages = ConversationHistory.read(context, notifId).toMutableList()
            val index = messages.indexOfFirst { eventId.isNotEmpty() && it.extras.getString(EVENT_KEY) == eventId }
            if (index >= 0 && !silent && state.showContent && state.showEncryptedContent) return
            // A repeated encrypted delivery must not replace an already decrypted preview.
            if (index >= 0) builder.setSilent(true)
            val incoming = NotificationCompat.MessagingStyle.Message(
                if (!state.showContent) "New message" else text.orEmpty(),
                if (index >= 0) messages[index].timestamp else System.currentTimeMillis(),
                sender.takeIf { it.isNotEmpty() }?.let { Person.Builder().setName(it).build() }
            ).also {
                it.extras.putString(EVENT_KEY, eventId)
                it.extras.putString(GENERATION_KEY, generation)
                it.extras.putBoolean(ENCRYPTED_KEY, notification.optString("type") == "m.room.encrypted")
            }
            if (index >= 0 && silent && messages[index].text.toString() == incoming.text.toString()) return
            if (index >= 0) {
                if (silent) messages[index] = incoming
            } else messages.add(incoming)
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName(SELF_PERSON_NAME).build())
            if (roomName.isNotEmpty()) {
                style.conversationTitle = roomName
                style.isGroupConversation = true
            }
            messages.takeLast(ConversationHistory.LIMIT).forEach { style.addMessage(it) }
            messages.lastOrNull()?.let { builder.setWhen(it.timestamp) }
            actionEventId = messages.lastOrNull()?.extras?.getString(EVENT_KEY)?.takeIf { it.isNotEmpty() } ?: eventId
            builder.setStyle(style)
        }

        val intent = buildPushIntent(context, notifId, roomId, actionEventId, userId)
        builder.setContentIntent(PendingIntent.getActivity(context, notifId, intent, flags))
        if (!isInvite) {
            addReplyAction(context, builder, notifId, roomId, actionEventId, userId, flags)
        }

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
        NotificationReceipts.record(context, notifId, eventId)
    }

    private fun postIncomingCall(context: Context, notification: JSONObject, event: JSONObject) {
        ensureChannels(context)

        val roomId = notification.optString("room_id")
        val userId = notification.optString("user_id")
        val eventId = event.optString("event_id").ifEmpty { notification.optString("event_id") }
        val caller = notification.optString("sender_display_name")
            .ifEmpty { notification.optString("room_name") }
            .ifEmpty { notification.optString("sender") }
            .ifEmpty { "Unknown caller" }

        val notifId = callNotificationId(userId, roomId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun pending(action: String) = PendingIntent.getActivity(
            context,
            notifId + action.hashCode(),
            buildPushIntent(context, notifId, roomId, eventId, userId, action, CALL_ACTION_TYPE_ID),
            flags,
        )

        val iconId = context.resources
            .getIdentifier("notification_icon", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val fullScreen = pending(DEFAULT_PRESS_ACTION)

        val builder = NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
            .setSmallIcon(iconId)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    Person.Builder().setName(caller).setImportant(true).build(),
                    pending(DECLINE_ACTION),
                    pending(ANSWER_ACTION),
                )
            )
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setTimeoutAfter(ringLifetimeMs(event))

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    /** Clamped: a broken sender clock must not ring forever. */
    private fun ringLifetimeMs(event: JSONObject): Long {
        val lifetime = event.optJSONObject("content")?.optLong("lifetime", 0L) ?: 0L
        if (lifetime <= 0L) return DEFAULT_RING_LIFETIME_MS
        return lifetime.coerceAtMost(MAX_RING_LIFETIME_MS)
    }

    /** Distinct from [roomNotificationId]: a call and a conversation coexist. */
    internal fun callNotificationId(userId: String, roomId: String): Int =
        roomNotificationId(userId, roomId + '\u0000' + "call")

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
            val actionFlags = when {
                action.input != true -> flags
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else -> PendingIntent.FLAG_UPDATE_CURRENT
            }
            val actionPendingIntent = PendingIntent.getActivity(
                context, notifId + action.id.hashCode(), actionIntent, actionFlags
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
        action: String = DEFAULT_PRESS_ACTION,
        actionTypeId: String = ACTION_TYPE_ID
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
            put("actionTypeId", actionTypeId)
        }.toString()
        intent.putExtra(NOTIFICATION_OBJ_INTENT_KEY, sourceJson)

        return intent
    }

    private fun messageText(context: Context, notification: JSONObject): String {
        if (!UnifiedPushStateStore(context).showContent) return "New message"
        if (notification.optString("type") != "m.room.encrypted") {
            PushDiagnostics.record(context, PushOutcome.PLAINTEXT)
            return notification.optJSONObject("content")
                ?.optString("body")
                ?.takeIf { it.isNotEmpty() }
                ?: "New message"
        }

        return "Encrypted message"
    }

    private fun encryptedEvent(notification: JSONObject): String? {
        val roomId = notification.optString("room_id").takeIf { it.isNotEmpty() } ?: return null
        val content = notification.optJSONObject("content") ?: return null
        return JSONObject()
            .put("type", "m.room.encrypted")
            .put("room_id", roomId)
            .put("content", content)
            .put("event_id", notification.optString("event_id"))
            .put("sender", notification.optString("sender"))
            .put("origin_server_ts", System.currentTimeMillis())
            .toString()
    }

    private fun decryptedEvent(
        context: Context,
        notification: JSONObject,
        userId: String,
        deviceId: String
    ): Pair<JSONObject?, PushOutcome> {
        val roomId = notification.optString("room_id")
        val event = encryptedEvent(notification) ?: return null to PushOutcome.NO_CONTENT

        val clear = when (
            val result = PushPayloadDecryptor.decrypt(context, userId, deviceId, roomId, event)
        ) {
            is PushDecryptResult.Success -> result.clearEventJson
            PushDecryptResult.Discard -> return null to PushOutcome.DISCARDED
            is PushDecryptResult.NeedsKey -> return null to PushOutcome.DECRYPT_FAILED
            is PushDecryptResult.Failure -> return null to result.outcome
        }

        val clearEvent = try {
            JSONObject(clear)
        } catch (_: Exception) {
            null
        }

        return if (clearEvent == null) {
            null to PushOutcome.EMPTY_BODY
        } else {
            clearEvent to PushOutcome.DECRYPTED
        }
    }

    private fun buildBody(sender: String, text: String): String {
        val prefix = if (sender.isNotEmpty()) "$sender: " else ""
        return "$prefix$text"
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
        ensureChannel(
            manager,
            CALLS_CHANNEL_ID,
            "Calls",
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH
        ) { channel ->
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            channel.enableVibration(true)
            channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
    }

    private fun ensureChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        channelDescription: String,
        importance: Int,
        configure: (NotificationChannel) -> Unit = {}
    ) {
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                description = channelDescription
                configure(this)
            }
        )
    }
}
