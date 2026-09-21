package app.tauri.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

internal object ConversationHistory {
    const val EVENT_KEY = "sable.push.event"
    const val ENCRYPTED_KEY = "sable.push.encrypted"
    const val LIMIT = 8

    fun read(context: Context, id: Int): List<NotificationCompat.MessagingStyle.Message> {
        val active = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.firstOrNull { it.id == id && it.tag == null } ?: return emptyList()
        val state = UnifiedPushStateStore(context)
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification)
            ?.messages.orEmpty().map { message ->
                val hidden = !state.showContent || (!state.showEncryptedContent &&
                    message.extras.getBoolean(ENCRYPTED_KEY, true))
                NotificationCompat.MessagingStyle.Message(
                    if (hidden) "New message" else message.text, message.timestamp, message.person
                ).also { it.extras.putAll(message.extras) }
            }
    }
}
