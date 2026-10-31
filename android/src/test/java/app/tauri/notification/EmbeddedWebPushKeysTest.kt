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

    private val keyFile get() = java.io.File(context.noBackupFilesDir, "embedded-webpush-keys.json")

    @Test
    fun `stores the private key outside of backups`() {
        assertNotNull(EmbeddedWebPushKeys.publicKeys(context))

        assertEquals(true, keyFile.exists())
        val legacy = context.getSharedPreferences("embedded-webpush-keys", android.content.Context.MODE_PRIVATE)
        assertEquals(emptyMap(), legacy.all)
    }

    @Test
    fun `migrates keys from the backed up preferences`() {
        val legacyKeys = org.json.JSONObject(
            run {
                assertNotNull(EmbeddedWebPushKeys.publicKeys(context))
                keyFile.readText()
            }
        )
        keyFile.delete()
        EmbeddedWebPushKeys.forgetCachedKeys()
        context.getSharedPreferences("embedded-webpush-keys", android.content.Context.MODE_PRIVATE).edit()
            .putString("private", legacyKeys.getString("private"))
            .putString("public", legacyKeys.getString("public"))
            .putString("auth", legacyKeys.getString("auth"))
            .commit()

        val keys = assertNotNull(EmbeddedWebPushKeys.publicKeys(context))

        assertEquals(legacyKeys.getString("public"), keys.p256dh)
        assertEquals(true, keyFile.exists())
        assertEquals(
            emptyMap(),
            context.getSharedPreferences("embedded-webpush-keys", android.content.Context.MODE_PRIVATE).all,
        )
    }

    @Test
    fun `regenerates keys that cannot be decoded`() {
        keyFile.parentFile!!.mkdirs()
        keyFile.writeText("""{"private":"!!","public":"!!","auth":"!!"}""")
        EmbeddedWebPushKeys.forgetCachedKeys()

        val keys = assertNotNull(EmbeddedWebPushKeys.publicKeys(context))

        assertEquals(65, decodeUrl(keys.p256dh).size)
    }

    @Test
    fun `concurrent callers share one keypair`() {
        EmbeddedWebPushKeys.forgetCachedKeys()
        keyFile.delete()
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = List(8) {
            Thread {
                start.await()
                results.add(assertNotNull(EmbeddedWebPushKeys.publicKeys(context)).p256dh)
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, results.toSet().size)
        assertEquals(results.first(), EmbeddedWebPushKeys.publicKeys(context)!!.p256dh)
    }
}
