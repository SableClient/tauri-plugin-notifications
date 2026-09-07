package app.tauri.notification

import android.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedPushEndpointTest {
    @Test
    fun `generated topics carry the up prefix the gateway requires`() {
        val topic = EmbeddedPushEndpoint.generateTopic()

        assertTrue(topic.startsWith("up"))
        assertEquals(14, topic.length)
        assertTrue(EmbeddedPushEndpoint.isValidTopic(topic))
    }

    @Test
    fun `generated topics are not repeated`() {
        val topics = (1..50).map { EmbeddedPushEndpoint.generateTopic() }.toSet()

        assertEquals(50, topics.size, "the topic is the credential, so it must not repeat")
    }

    @Test
    fun `rejects topics without the up prefix`() {
        assertFalse(EmbeddedPushEndpoint.isValidTopic("mytopic"))
        assertFalse(EmbeddedPushEndpoint.isValidTopic("up"))
        assertFalse(EmbeddedPushEndpoint.isValidTopic(null))
        assertFalse(EmbeddedPushEndpoint.isValidTopic("up0123456789abcdef01234567"))
        assertFalse(EmbeddedPushEndpoint.isValidTopic("up topic/with-slash"))
    }

    @Test
    fun `normalises gateways by trimming whitespace and trailing slashes`() {
        assertEquals("https://ntfy.sh", EmbeddedPushEndpoint.normalizeGateway("  https://ntfy.sh/  "))
        assertEquals("https://push.example.org", EmbeddedPushEndpoint.normalizeGateway("https://push.example.org"))
        assertEquals("https://ntfy.sh/base", EmbeddedPushEndpoint.normalizeGateway("https://ntfy.sh/base/"))
    }

    @Test
    fun `rejects gateways that are not http or https`() {
        assertNull(EmbeddedPushEndpoint.normalizeGateway("wss://ntfy.sh"))
        assertNull(EmbeddedPushEndpoint.normalizeGateway("ntfy.sh"))
        assertNull(EmbeddedPushEndpoint.normalizeGateway("https://"))
        assertNull(EmbeddedPushEndpoint.normalizeGateway(""))
        assertNull(EmbeddedPushEndpoint.normalizeGateway(null))
    }

    @Test
    fun `builds the endpoint the homeserver pushes to`() {
        assertEquals(
            "https://ntfy.sh/up0123456789ab?up=1",
            EmbeddedPushEndpoint.endpointUrl("https://ntfy.sh/", "up0123456789ab"),
        )
    }

    @Test
    fun `maps https to wss and http to ws`() {
        assertEquals(
            "wss://ntfy.sh/up0123456789ab/ws",
            EmbeddedPushEndpoint.webSocketUrl("https://ntfy.sh", "up0123456789ab"),
        )
        assertEquals(
            "ws://localhost:2586/up0123456789ab/ws",
            EmbeddedPushEndpoint.webSocketUrl("http://localhost:2586", "up0123456789ab"),
        )
    }

    @Test
    fun `refuses to build urls from an invalid topic or gateway`() {
        assertNull(EmbeddedPushEndpoint.endpointUrl("https://ntfy.sh", "nope"))
        assertNull(EmbeddedPushEndpoint.webSocketUrl("ftp://ntfy.sh", "up0123456789ab"))
    }

    @Test
    fun `recovers the websocket url from a persisted endpoint`() {
        assertEquals(
            "wss://ntfy.sh/up0123456789ab/ws",
            EmbeddedPushEndpoint.webSocketUrlForEndpoint("https://ntfy.sh/up0123456789ab"),
        )
        assertEquals(
            "wss://push.example.org/base/up0123456789ab/ws",
            EmbeddedPushEndpoint.webSocketUrlForEndpoint("https://push.example.org/base/up0123456789ab"),
        )
    }

    @Test
    fun `recovers the websocket url from a UnifiedPush endpoint`() {
        assertEquals(
            "wss://push.example.org/base/up0123456789ab/ws",
            EmbeddedPushEndpoint.webSocketUrlForEndpoint("https://push.example.org/base/up0123456789ab?up=1"),
        )
    }

    @Test
    fun `unwraps the homeserver body from a gateway message frame`() {
        val frame = """{"id":"x","time":1,"event":"message","topic":"upabc","message":"{\"room_id\":\"!r:e.org\"}"}"""

        assertEquals("""{"room_id":"!r:e.org"}""", EmbeddedPushEndpoint.pushBody(frame)?.decodeToString())
    }

    @Test
    fun `decodes an encrypted body the gateway had to base64`() {
        val sealed = byteArrayOf(0x00, 0x7F, -0x80, -0x01)
        val encoded = Base64.encodeToString(sealed, Base64.NO_WRAP)
        val frame = """{"event":"message","topic":"upabc","message":"$encoded","encoding":"base64"}"""

        assertContentEquals(sealed, EmbeddedPushEndpoint.pushBody(frame))
    }

    @Test
    fun `ignores a base64 body the gateway mangled`() {
        assertNull(
            EmbeddedPushEndpoint.pushBody(
                """{"event":"message","topic":"upabc","message":"!!!not base64!!!","encoding":"base64"}""",
            ),
        )
    }

    @Test
    fun `ignores frames that carry no push payload`() {
        assertNull(EmbeddedPushEndpoint.pushBody("""{"id":"x","event":"open","topic":"upabc"}"""))
        assertNull(EmbeddedPushEndpoint.pushBody("""{"id":"x","event":"keepalive","topic":"upabc"}"""))
        assertNull(EmbeddedPushEndpoint.pushBody("""{"event":"message","topic":"upabc"}"""))
        assertNull(EmbeddedPushEndpoint.pushBody("not json"))
    }

    @Test
    fun `does not recover a websocket url from a foreign endpoint`() {
        assertNull(EmbeddedPushEndpoint.webSocketUrlForEndpoint("https://fcm.googleapis.com/fcm/send/abc"))
        assertNull(EmbeddedPushEndpoint.webSocketUrlForEndpoint(null))
    }
}
