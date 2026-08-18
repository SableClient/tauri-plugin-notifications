package app.tauri.notification

import android.util.Base64
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EmbeddedWebPushKeysTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun decodeUrl(value: String) =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    @Test
    fun `publishes a p256dh the homeserver can encrypt to`() {
        val keys = assertNotNull(EmbeddedWebPushKeys.publicKeys(context))

        val point = decodeUrl(keys.p256dh)
        assertEquals(65, point.size, "WebPush requires the uncompressed point")
        assertEquals(0x04.toByte(), point[0])
        assertEquals(16, decodeUrl(keys.auth).size, "the auth secret is 16 bytes")
    }

    @Test
    fun `keeps the same subscription across calls`() {
        val first = assertNotNull(EmbeddedWebPushKeys.publicKeys(context))
        val second = assertNotNull(EmbeddedWebPushKeys.publicKeys(context))

        assertEquals(first.p256dh, second.p256dh, "rotating would silently break the pusher")
        assertEquals(first.auth, second.auth)
    }

    @Test
    fun `decrypts a body encrypted to the published keys`() {
        val keys = assertNotNull(EmbeddedWebPushKeys.publicKeys(context))
        val body = """{"room_id":"!r:e.org"}""".toByteArray()

        val sealed = WebPushHybridEncrypt.Builder()
            .withAuthSecret(decodeUrl(keys.auth))
            .withRecipientPublicKey(decodeUrl(keys.p256dh))
            .build()
            .encrypt(body, null)

        assertContentEquals(body, EmbeddedWebPushKeys.decrypt(context, sealed))
    }

    @Test
    fun `rejects a body encrypted to someone else`() {
        val other = WebPushHybridEncrypt.Builder()
            .withAuthSecret(ByteArray(16) { 7 })
            .withRecipientPublicKey(
                decodeUrl(assertNotNull(EmbeddedWebPushKeys.publicKeys(context)).p256dh)
            )
            .build()
            .encrypt("payload".toByteArray(), null)

        assertNull(EmbeddedWebPushKeys.decrypt(context, other))
    }

    @Test
    fun `rejects a body that is not webpush at all`() {
        assertNull(EmbeddedWebPushKeys.decrypt(context, "plain text".toByteArray()))
        assertNull(EmbeddedWebPushKeys.decrypt(context, ByteArray(0)))
    }
}
