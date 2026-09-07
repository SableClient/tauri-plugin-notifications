package app.tauri.notification

import android.util.Base64
import java.security.SecureRandom
import java.util.Locale
import org.json.JSONObject

internal object EmbeddedPushEndpoint {
    /** Gateways only grant anonymous write access under `up*`; other topics reject the push. */
    const val TOPIC_PREFIX = "up"

    private const val TOPIC_RANDOM_LENGTH = 12
    private const val TOPIC_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val WS_PATH = "ws"
    private const val ENCODING_BASE64 = "base64"

    fun generateTopic(random: SecureRandom = SecureRandom()): String {
        return TOPIC_PREFIX + buildString {
            repeat(TOPIC_RANDOM_LENGTH) {
                append(TOPIC_ALPHABET[random.nextInt(TOPIC_ALPHABET.length)])
            }
        }
    }

    fun isValidTopic(topic: String?): Boolean =
        topic != null &&
            topic.startsWith(TOPIC_PREFIX) &&
            topic.length == TOPIC_PREFIX.length + TOPIC_RANDOM_LENGTH &&
            topic.all { it in TOPIC_ALPHABET }

    fun normalizeGateway(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null

        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (trimmed.substringAfter("://", missingDelimiterValue = "").isEmpty()) return null

        return scheme + "://" + trimmed.substringAfter("://")
    }

    fun endpointUrl(gateway: String, topic: String): String? {
        val base = normalizeGateway(gateway) ?: return null
        if (!isValidTopic(topic)) return null
        return "$base/$topic?up=1"
    }

    fun webSocketUrl(gateway: String, topic: String): String? {
        val endpoint = endpointUrl(gateway, topic)?.substringBefore('?') ?: return null
        val wsEndpoint = when {
            endpoint.startsWith("https://") -> "wss://" + endpoint.removePrefix("https://")
            else -> "ws://" + endpoint.removePrefix("http://")
        }
        return "$wsEndpoint/$WS_PATH"
    }

    /**
     * The body the homeserver pushed, still encrypted. `open` and `keepalive` frames
     * carry no payload; an encrypted body is not valid UTF-8, so it arrives base64.
     */
    fun pushBody(frame: String): ByteArray? {
        val json = try {
            JSONObject(frame)
        } catch (_: Exception) {
            return null
        }
        if (json.optString("event") != "message") return null

        val message = json.optString("message").takeIf { it.isNotEmpty() } ?: return null
        if (json.optString("encoding") != ENCODING_BASE64) return message.toByteArray()

        return try {
            Base64.decode(message, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun webSocketUrlForEndpoint(endpoint: String?): String? {
        val trimmed = endpoint?.trim()?.substringBefore('?')?.trimEnd('/') ?: return null
        val topic = trimmed.substringAfterLast('/', missingDelimiterValue = "")
        if (!isValidTopic(topic)) return null
        val gateway = trimmed.removeSuffix("/$topic")
        return webSocketUrl(gateway, topic)
    }
}
