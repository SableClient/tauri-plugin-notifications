package app.tauri.notification

import android.os.Looper
import android.util.Base64
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddedPushServiceTest {
    private lateinit var service: EmbeddedPushService
    private lateinit var state: UnifiedPushStateStore
    private lateinit var plugin: NotificationPlugin
    private val connections = mutableListOf<Pair<WebSocket, WebSocketListener>>()
    private val urls = mutableListOf<String>()
    private val endpoint = "https://ntfy.sh/up0123456789ab?up=1"

    @Before
    fun setup() {
        service = Robolectric.buildService(EmbeddedPushService::class.java).create().get()
        state = UnifiedPushStateStore(service)
        state.activeProvider = "embedded"
        state.endpoint = endpoint
        plugin = mockk(relaxed = true)
        NotificationPlugin.instance = plugin
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newWebSocket(any(), any()) } answers {
            val socket = mockk<WebSocket>(relaxed = true)
            connections.add(socket to secondArg())
            urls.add(firstArg<Request>().url.toString())
            socket
        }
        EmbeddedPushService::class.java.getDeclaredField("client").apply {
            isAccessible = true
            set(service, client)
        }
    }

    @After
    fun teardown() {
        service.onDestroy()
        NotificationPlugin.instance = null
    }

    private fun start() {
        service.onStartCommand(null, 0, 1)
    }

    private fun frame(index: Int, text: String) {
        val (socket, listener) = connections[index]
        listener.onMessage(socket, text)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun waitsForSubscriberReadiness() {
        start()
        val (socket, listener) = connections.single()
        listener.onOpen(socket, mockk())
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { plugin.onEmbeddedPushReady(any()) }

        frame(0, """{"event":"open"}""")

        verify(exactly = 1) { plugin.onEmbeddedPushReady(endpoint) }
        assertEquals(listOf("https://ntfy.sh/up0123456789ab/ws"), urls)
    }

    @Test
    fun decryptsWebPushBytesReceivedOverWebSocket() {
        val keys = EmbeddedWebPushKeys.publicKeys(service)!!
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val body = """{"app_id":"app","ack_token":"activation-token"}"""
        val sealed = WebPushHybridEncrypt.Builder()
            .withAuthSecret(Base64.decode(keys.auth, flags))
            .withRecipientPublicKey(Base64.decode(keys.p256dh, flags))
            .build()
            .encrypt(body.toByteArray(), null)
        val encoded = Base64.encodeToString(sealed, Base64.NO_WRAP)
        start()
        frame(0, """{"event":"open"}""")
        frame(0, """{"event":"message","encoding":"base64","message":"$encoded"}""")

        val executor = EmbeddedPushService::class.java.getDeclaredField("pushExecutor").run {
            isAccessible = true
            get(service) as ExecutorService
        }
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { plugin.onUnifiedPushMessage(body, UnifiedPushStateStore.INSTANCE) }
    }

    @Test
    fun reusesReadyConnectionForRegistration() {
        start()
        frame(0, """{"event":"open"}""")
        start()

        assertEquals(1, connections.size)
        verify(exactly = 2) { plugin.onEmbeddedPushReady(endpoint) }
    }

    @Test
    fun replacesConnectionAndIgnoresOldCallbacks() {
        start()
        val (oldSocket, oldListener) = connections.single()
        state.endpoint = "https://push.example.org/up0123456789ab?up=1"
        start()
        oldListener.onFailure(oldSocket, IOException("disconnected"), null)
        frame(0, """{"event":"open"}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        verify { oldSocket.cancel() }
        verify(exactly = 0) { plugin.onEmbeddedPushReady(any()) }
        assertEquals(2, connections.size)
        frame(1, """{"event":"open"}""")
        verify(exactly = 1) { plugin.onEmbeddedPushReady(state.endpoint!!) }
    }

    @Test
    fun reconnectsOnceAfterDuplicateCloseCallbacks() {
        start()
        val (socket, listener) = connections.single()
        listener.onFailure(socket, IOException("disconnected"), null)
        listener.onClosed(socket, 1000, "closed")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(2, connections.size)
    }

    @Test
    fun cancelsReconnectWhenStopped() {
        start()
        val (socket, listener) = connections.single()
        listener.onFailure(socket, IOException("disconnected"), null)
        shadowOf(Looper.getMainLooper()).idle()
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))

        assertEquals(1, connections.size)
    }

    @Test
    fun keepsBackoffBoundedDuringLongOutages() {
        start()
        repeat(70) {
            val (socket, listener) = connections.last()
            listener.onFailure(socket, IOException("offline"), null)
            shadowOf(Looper.getMainLooper()).idle()
            val beforeRetry = connections.size
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
            assertEquals(beforeRetry, connections.size)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
            assertEquals(beforeRetry + 1, connections.size)
        }
    }
}
