package app.tauri.notification

import android.net.ConnectivityManager
import android.os.Looper
import android.util.Base64
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
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
        state.prepareEmbeddedReplay(endpoint, true)
        plugin = mockk(relaxed = true)
        every { plugin.onUnifiedPushMessage(any(), any()) } returns true
        NotificationPlugin.instance = plugin
        PushDiagnostics.drain(service)
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
        finishWork()
    }

    @Test
    fun waitsForSubscriberReadiness() {
        start()
        val (socket, listener) = connections.single()
        listener.onOpen(socket, mockk())
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { plugin.onEmbeddedPushReady(any()) }
        assertEquals(mapOf("EMBEDDED_STARTED" to 1), PushDiagnostics.drain(service).counts)

        frame(0, """{"event":"open","time":100}""")

        verify(exactly = 1) { plugin.onEmbeddedPushReady(endpoint) }
        assertEquals(listOf("https://ntfy.sh/up0123456789ab/ws?since=12h"), urls)
        assertEquals(mapOf("EMBEDDED_READY" to 1), PushDiagnostics.drain(service).counts)
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
        frame(0, """{"event":"open","time":100}""")
        frame(0, """{"event":"message","id":"encrypted","time":200,"encoding":"base64","message":"$encoded"}""")

        finishWork()
        verify(exactly = 1) { plugin.onUnifiedPushMessage(body, UnifiedPushStateStore.INSTANCE) }
        val counts = PushDiagnostics.drain(service).counts
        assertEquals(1, counts["EMBEDDED_MESSAGE_RECEIVED"])
        assertEquals(1, counts["EMBEDDED_DECRYPTED"])
    }

    private fun finishWork() {
        val executor = EmbeddedPushService::class.java.getDeclaredField("pushExecutor").run {
            isAccessible = true
            get(service) as ExecutorService
        }
        repeat(3) {
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun message(id: String): String = org.json.JSONObject().apply {
        put("event", "message")
        put("id", id)
        put("time", 200)
        put("message", """{"app_id":"app","ack_token":"$id"}""")
    }.toString()

    @Test
    fun doesNotRepeatCompletedMessagesOnReconnect() {
        start()
        frame(0, """{"event":"open","time":100}""")
        finishWork()
        frame(0, message("A"))
        finishWork()
        val (socket, listener) = connections[0]
        listener.onFailure(socket, IOException("offline"), null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        frame(1, """{"event":"open","time":300}""")
        finishWork()
        frame(1, message("A"))
        finishWork()

        verify(exactly = 1) { plugin.onUnifiedPushMessage(any(), UnifiedPushStateStore.INSTANCE) }
    }

    @Test
    fun liveMessageDoesNotSkipEarlierReplay() {
        start()
        frame(0, """{"event":"open","time":100}""")
        finishWork()
        frame(0, message("C"))
        frame(0, message("A"))
        frame(0, message("B"))
        finishWork()
        frame(0, message("A"))
        frame(0, message("B"))
        frame(0, message("C"))
        finishWork()

        verify(exactly = 3) { plugin.onUnifiedPushMessage(any(), UnifiedPushStateStore.INSTANCE) }
    }

    @Test
    fun socketReplacementPreservesAcceptedDispatch() {
        start()
        frame(0, """{"event":"open","time":100}""")
        val executor = EmbeddedPushService::class.java.getDeclaredField("pushExecutor").run {
            isAccessible = true
            get(service) as ExecutorService
        }
        val release = CountDownLatch(1)
        executor.submit { release.await(5, TimeUnit.SECONDS) }
        try {
            val (socket, listener) = connections[0]
            listener.onMessage(socket, message("A"))
            shadowOf(Looper.getMainLooper()).idle()
            val manager = service.getSystemService(ConnectivityManager::class.java)
            shadowOf(manager).networkCallbacks.single().onAvailable(ShadowNetwork.newInstance(42))
            shadowOf(Looper.getMainLooper()).idle()
            verify { socket.cancel() }
        } finally {
            release.countDown()
        }
        finishWork()
        frame(1, """{"event":"open","time":300}""")
        frame(1, message("A"))

        verify(exactly = 1) { plugin.onUnifiedPushMessage(any(), UnifiedPushStateStore.INSTANCE) }
    }

    @Test
    fun completedMessagesSurviveServiceRestart() {
        start()
        frame(0, """{"event":"open","time":100}""")
        frame(0, message("A"))
        val clientField = EmbeddedPushService::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = clientField.get(service)
        service.onDestroy()
        service = Robolectric.buildService(EmbeddedPushService::class.java).create().get()
        clientField.set(service, client)
        state = UnifiedPushStateStore(service)
        start()
        frame(1, """{"event":"open","time":300}""")
        frame(1, message("A"))
        frame(1, message("B"))

        verify(exactly = 2) { plugin.onUnifiedPushMessage(any(), UnifiedPushStateStore.INSTANCE) }
    }

    @Test
    fun activationWithoutListenerRemainsRetryable() {
        every { plugin.onUnifiedPushMessage(any(), any()) } returns false
        start()
        frame(0, """{"event":"open","time":100}""")
        frame(0, message("A"))
        assertEquals(true, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
        every { plugin.onUnifiedPushMessage(any(), any()) } returns true
        frame(0, message("A"))
        assertEquals(false, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
    }

    @Test
    fun legacyTopicDoesNotReplayHistoricalAlerts() {
        val legacy = "https://ntfy.sh/up9876543210ab?up=1"
        state.endpoint = legacy
        state.prepareEmbeddedReplay(legacy, false)
        start()
        frame(0, """{"event":"open","time":300}""")
        frame(0, message("old"))
        frame(0, message("new").replace("200", "300"))

        verify(exactly = 1) { plugin.onUnifiedPushMessage(any(), UnifiedPushStateStore.INSTANCE) }
        assertEquals(false, state.shouldProcessEmbeddedPush(endpoint, "new", 300))
    }

    @Test
    fun networkReturnCancelsBackoffAndIgnoresLateFailure() {
        start()
        val (socket, listener) = connections[0]
        listener.onFailure(socket, IOException("offline"), null)
        shadowOf(Looper.getMainLooper()).idle()
        val manager = service.getSystemService(ConnectivityManager::class.java)
        val callback = shadowOf(manager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(42)
        callback.onAvailable(network)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, connections.size)
        callback.onAvailable(network)
        listener.onFailure(socket, IOException("late failure"), null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, connections.size)
        service.onDestroy()
        callback.onAvailable(ShadowNetwork.newInstance(43))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, connections.size)
    }

    @Test
    fun replaysFromLocalGatewayWhenConfigured() {
        val gateway = System.getenv("SABLE_NTFY_TEST_URL")
        org.junit.Assume.assumeTrue(gateway != null)
        val topic = EmbeddedPushEndpoint.generateTopic()
        val localEndpoint = EmbeddedPushEndpoint.endpointUrl(gateway!!, topic)!!
        state.endpoint = localEndpoint
        state.prepareEmbeddedReplay(localEndpoint, true)
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val readyCount = java.util.concurrent.atomic.AtomicInteger()
        every { plugin.onUnifiedPushMessage(any(), any()) } answers {
            received.add(firstArg())
            true
        }
        every { plugin.onEmbeddedPushReady(any()) } answers { readyCount.incrementAndGet(); Unit }
        val clientField = EmbeddedPushService::class.java.getDeclaredField("client").apply { isAccessible = true }
        clientField.set(service, OkHttpClient())
        fun awaitCondition(check: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!check() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            org.junit.Assert.assertTrue("Timed out waiting for gateway delivery", check())
        }
        fun restart() {
            service.onDestroy()
            service = Robolectric.buildService(EmbeddedPushService::class.java).create().get()
            clientField.set(service, OkHttpClient())
            start()
        }
        start()
        awaitCondition { readyCount.get() == 1 }
        service.onDestroy()
        val keys = EmbeddedWebPushKeys.publicKeys(service)!!
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val publisher = OkHttpClient()
        try {
            for (id in listOf("offline-A", "offline-B")) {
                val body = """{"app_id":"app","ack_token":"$id"}"""
                val sealed = WebPushHybridEncrypt.Builder()
                    .withAuthSecret(Base64.decode(keys.auth, flags))
                    .withRecipientPublicKey(Base64.decode(keys.p256dh, flags))
                    .build().encrypt(body.toByteArray(), null)
                publisher.newCall(Request.Builder().url(localEndpoint).post(sealed.toRequestBody()).build())
                    .execute().use { assertEquals(200, it.code) }
            }
            restart()
            awaitCondition { received.size == 2 }
            finishWork()
            restart()
            awaitCondition { readyCount.get() == 3 }
            val prefs = service.getSharedPreferences("tauri-notifications", android.content.Context.MODE_PRIVATE)
            awaitCondition { prefs.getInt("push-outcome-EMBEDDED_MESSAGE_RECEIVED", 0) >= 4 }
            finishWork()
            assertEquals(2, received.size)
        } finally {
            publisher.dispatcher.executorService.shutdown()
            publisher.connectionPool.evictAll()
        }
    }

    @Test
    fun duplicateFramesQueuedBeforeProcessingDispatchOnce() {
        start()
        frame(0, """{"event":"open","time":100}""")
        val executor = EmbeddedPushService::class.java.getDeclaredField("pushExecutor").run {
            isAccessible = true
            get(service) as ExecutorService
        }
        val release = CountDownLatch(1)
        executor.submit { release.await(5, TimeUnit.SECONDS) }
        try {
            val (socket, listener) = connections[0]
            listener.onMessage(socket, message("A"))
            listener.onMessage(socket, message("A"))
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            release.countDown()
        }
        finishWork()
        verify(exactly = 1) { plugin.onUnifiedPushMessage(any(), UnifiedPushStateStore.INSTANCE) }
    }

    @Test
    fun destructionBeforeJsDispatchDoesNotCompleteMessage() {
        start()
        frame(0, """{"event":"open","time":100}""")
        val executor = EmbeddedPushService::class.java.getDeclaredField("pushExecutor").run {
            isAccessible = true
            get(service) as ExecutorService
        }
        val rendering = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockkObject(UnifiedPushNotifier)
        every { UnifiedPushNotifier.showFromPush(any(), any()) } answers {
            rendering.countDown()
            release.await(5, TimeUnit.SECONDS)
            Unit
        }
        try {
            val (socket, listener) = connections[0]
            listener.onMessage(socket, message("A"))
            shadowOf(Looper.getMainLooper()).idle()
            org.junit.Assert.assertTrue(rendering.await(5, TimeUnit.SECONDS))
            release.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            service.onDestroy()
            shadowOf(Looper.getMainLooper()).idle()
            verify(exactly = 0) { plugin.onUnifiedPushMessage(any(), any()) }
            assertEquals(true, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
        } finally {
            release.countDown()
            unmockkObject(UnifiedPushNotifier)
        }
    }

    @Test
    fun messagesBeforeOpenWaitForInitialization() {
        start()
        frame(0, message("A"))
        verify(exactly = 0) { plugin.onUnifiedPushMessage(any(), any()) }
        frame(0, """{"event":"open","time":100}""")
        verify(exactly = 1) { plugin.onUnifiedPushMessage(any(), any()) }
    }

    @Test
    fun changedAccountCannotReceiveQueuedMessage() {
        start()
        frame(0, """{"event":"open","time":100}""")
        state.pushUserId = "@other:example.org"
        frame(0, message("A"))
        verify(exactly = 0) { plugin.onUnifiedPushMessage(any(), any()) }
        assertEquals(true, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
    }

    @Test
    fun replayStateClearsWithRegistrationAndRetainsRecentIds() {
        assertEquals(true, state.initializeEmbeddedReplay(endpoint, 100))
        assertEquals(true, state.completeEmbeddedPush(endpoint, "A"))
        assertEquals(true, state.initializeEmbeddedReplay(endpoint, 300))
        assertEquals(false, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
        state.clearRegistration()
        state.endpoint = endpoint
        state.prepareEmbeddedReplay(endpoint, true)
        assertEquals(true, state.initializeEmbeddedReplay(endpoint, 400))
        assertEquals(true, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
    }

    @Test
    fun transientDecryptionFailureRemainsRetryable() {
        start()
        frame(0, """{"event":"open","time":100}""")
        val sealed = """{"event":"message","id":"A","time":200,"encoding":"base64","message":"AQID"}"""
        mockkObject(EmbeddedWebPushKeys)
        try {
            every { EmbeddedWebPushKeys.decrypt(any(), any()) } returns null
            frame(0, sealed)
            assertEquals(true, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
            every { EmbeddedWebPushKeys.decrypt(any(), any()) } returns
                """{"app_id":"app","ack_token":"A"}""".toByteArray()
            frame(0, sealed)
            assertEquals(false, state.shouldProcessEmbeddedPush(endpoint, "A", 200))
            verify(exactly = 1) { plugin.onUnifiedPushMessage(any(), any()) }
        } finally {
            unmockkObject(EmbeddedWebPushKeys)
        }
    }

    @Test
    fun reusesReadyConnectionForRegistration() {
        start()
        frame(0, """{"event":"open","time":100}""")
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
        frame(0, """{"event":"open","time":100}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        verify { oldSocket.cancel() }
        verify(exactly = 0) { plugin.onEmbeddedPushReady(any()) }
        assertEquals(2, connections.size)
        frame(1, """{"event":"open","time":100}""")
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
        assertEquals(1, PushDiagnostics.drain(service).counts["EMBEDDED_SOCKET_FAILED"])
        assertEquals(emptyMap(), PushDiagnostics.drain(service).counts)
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
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(119_001))
            assertEquals(beforeRetry + 1, connections.size)
        }
    }
}
