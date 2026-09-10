package app.tauri.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Holds the gateway websocket open when nothing else can deliver. Foreground because
 * Android only keeps a socket alive in a process doing user-visible work.
 */
class EmbeddedPushService : Service() {
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    @Volatile private var closing = false
    private var retryDelay = BASE_BACKOFF_MS
    private val handler = Handler(Looper.getMainLooper())
    private var endpoint: String? = null
    private var activeSubscription: Subscription? = null
    private var ready = false
    private val pushExecutor = Executors.newSingleThreadExecutor()
    private var currentNetwork: Network? = null
    private var connectivity: ConnectivityManager? = null
    private val inFlight = mutableSetOf<Pair<Subscription, String>>()

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    private data class Subscription(val endpoint: String, val userId: String?, val deviceId: String?)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (closing || network == currentNetwork) return@post
                currentNetwork = network
                val url = EmbeddedPushEndpoint.webSocketUrlForEndpoint(endpoint) ?: return@post
                cancelReconnect()
                retryDelay = BASE_BACKOFF_MS
                socket?.cancel()
                socket = null
                ready = false
                connect(url)
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                if (currentNetwork == network) currentNetwork = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(ConnectivityManager::class.java)
        currentNetwork = manager.activeNetwork
        manager.registerDefaultNetworkCallback(networkCallback)
        connectivity = manager
    }

    private fun cancelReconnect() {
        EmbeddedPushAlarm.cancel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()

        val state = UnifiedPushStateStore(this)
        val nextEndpoint = state.endpoint
        val url = if (state.activeProvider == "embedded") {
            EmbeddedPushEndpoint.webSocketUrlForEndpoint(nextEndpoint)
        } else null
        if (url == null) {
            Log.w(TAG, "No embedded push endpoint to connect to; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val nextSubscription = Subscription(nextEndpoint!!, state.pushUserId, state.pushDeviceId)
        if (activeSubscription != nextSubscription) {
            activeSubscription = nextSubscription
            PushDiagnostics.record(this, PushOutcome.EMBEDDED_STARTED)
            cancelReconnect()
            socket?.cancel()
            socket = null
            ready = false
            endpoint = nextEndpoint
            retryDelay = BASE_BACKOFF_MS
        }
        if (intent?.getBooleanExtra("replay", false) == true) {
            cancelReconnect()
            socket?.cancel()
            socket = null
            ready = false
        }
        if (socket == null) {
            cancelReconnect()
            connect(url)
        } else {
            EmbeddedPushAlarm.schedule(this, HEARTBEAT_MS)
            if (ready) endpoint?.let { NotificationPlugin.instance?.onEmbeddedPushReady(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        closing = true
        connectivity?.unregisterNetworkCallback(networkCallback)
        connectivity = null
        cancelReconnect()
        releaseWakeLock()
        ready = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        pushExecutor.shutdown()
        super.onDestroy()
    }

    private fun connect(url: String) {
        if (!wakeLock.isHeld) wakeLock.acquire(CONNECT_WAKELOCK_MS)

        val http = client ?: OkHttpClient.Builder()
            // The gateway sends its own keepalives; this catches a half-open socket.
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
            .also { client = it }

        val state = UnifiedPushStateStore(this)
        if (state.activeProvider != "embedded" || state.endpoint != endpoint) return
        val owner = Subscription(state.endpoint ?: return, state.pushUserId, state.pushDeviceId)
        val requestUrl = Request.Builder().url(url).build().url.newBuilder().addQueryParameter("since", "12h").build()
        socket = http.newWebSocket(Request.Builder().url(requestUrl).build(), Listener(owner))
    }

    private fun scheduleReconnect() {
        if (closing) return
        val delay = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(MAX_BACKOFF_MS)
        Log.i(TAG, "Reconnecting to the push gateway in ${delay}ms")
        cancelReconnect()
        releaseWakeLock()
        EmbeddedPushAlarm.schedule(this, delay)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun owns(owner: Subscription): Boolean {
        val state = UnifiedPushStateStore(this)
        return !closing && state.activeProvider == "embedded" && state.endpoint == owner.endpoint &&
            state.pushUserId == owner.userId && state.pushDeviceId == owner.deviceId
    }

    private fun processMessage(owner: Subscription, text: String, json: JSONObject) {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return
        val key = owner to id
        if (!owns(owner) || !inFlight.add(key)) return
        val state = UnifiedPushStateStore(this)
        pushExecutor.execute {
            if (!owns(owner) || !state.shouldProcessEmbeddedPush(owner.endpoint, id, json.optLong("time"))) {
                handler.post { inFlight.remove(key) }
                return@execute
            }
            val sealed = EmbeddedPushEndpoint.pushBody(text)
            if (sealed == null) {
                state.completeEmbeddedPush(owner.endpoint, id)
                handler.post { inFlight.remove(key) }
                return@execute
            }
            val body = decrypt(sealed)
            if (body == null || !owns(owner)) {
                handler.post { inFlight.remove(key) }
                return@execute
            }
            PushDiagnostics.record(this, PushOutcome.EMBEDDED_DECRYPTED)
            val payload = MatrixPushPayload.parse(body)
            val activation = payload?.optString("ack_token")?.isNotEmpty() == true
            try {
                UnifiedPushNotifier.showFromPush(this, body)
            } catch (_: Exception) {
                Log.w(TAG, "Could not display the push notification")
                handler.post { inFlight.remove(key) }
                return@execute
            }
            handler.post {
                if (!owns(owner)) {
                    inFlight.remove(key)
                    return@post
                }
                val dispatched = try {
                    NotificationPlugin.instance?.onUnifiedPushMessage(body, UnifiedPushStateStore.INSTANCE) == true
                } catch (_: Exception) {
                    inFlight.remove(key)
                    return@post
                }
                if (activation && !dispatched) {
                    inFlight.remove(key)
                    return@post
                }
                pushExecutor.execute {
                    if (owns(owner) && !state.completeEmbeddedPush(owner.endpoint, id)) {
                        Log.w(TAG, "Could not persist push completion")
                    }
                    handler.post { inFlight.remove(key) }
                }
            }
        }
    }

    private inner class Listener(private val owner: Subscription) : WebSocketListener() {
        private val pending = mutableListOf<Pair<String, JSONObject>>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            handler.post {
                if (closing || socket !== webSocket) return@post
                val json = try { JSONObject(text) } catch (_: Exception) { return@post }
                when (json.optString("event")) {
                    "open" -> {
                        pushExecutor.execute {
                            val initialized = UnifiedPushStateStore(this@EmbeddedPushService)
                                .initializeEmbeddedReplay(owner.endpoint, json.optLong("time"))
                            handler.post ready@{
                                if (!owns(owner) || socket !== webSocket) return@ready
                                if (!initialized) {
                                    webSocket.cancel()
                                    return@ready
                                }
                                Log.i(TAG, "Push gateway subscription ready")
                                releaseWakeLock()
                                EmbeddedPushAlarm.schedule(this@EmbeddedPushService, HEARTBEAT_MS)
                                PushDiagnostics.record(this@EmbeddedPushService, PushOutcome.EMBEDDED_READY)
                                ready = true
                                retryDelay = BASE_BACKOFF_MS
                                NotificationPlugin.instance?.onEmbeddedPushReady(owner.endpoint)
                                pending.forEach { (frame, payload) -> processMessage(owner, frame, payload) }
                                pending.clear()
                            }
                        }
                    }
                    "message" -> {
                        PushDiagnostics.record(this@EmbeddedPushService, PushOutcome.EMBEDDED_MESSAGE_RECEIVED)
                        if (ready) processMessage(owner, text, json) else pending.add(text to json)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            disconnected(webSocket, if (response != null) PushOutcome.EMBEDDED_HTTP_REJECTED else PushOutcome.EMBEDDED_SOCKET_FAILED)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(webSocket, PushOutcome.EMBEDDED_CLOSED)
        }

        private fun disconnected(webSocket: WebSocket, outcome: PushOutcome) {
            handler.post {
                if (closing || socket !== webSocket) return@post
                PushDiagnostics.record(this@EmbeddedPushService, outcome)
                socket = null
                ready = false
                scheduleReconnect()
            }
        }
    }

    /** The gateway relays the body untouched, so it is still encrypted to our keys. */
    private fun decrypt(sealed: ByteArray): String? {
        EmbeddedWebPushKeys.decrypt(this, sealed)?.let { return String(it) }

        // A plaintext relay is used for testing; not a failure worth logging.
        if (sealed.isNotEmpty() && sealed[0] == '{'.code.toByte()) return String(sealed)

        PushDiagnostics.record(this, PushOutcome.EMBEDDED_DECRYPT_FAILED)
        Log.w(TAG, "Could not decrypt the push body")
        return null
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN)
                    .apply { description = CHANNEL_DESCRIPTION },
            )
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        // `dataSync` would be capped at 6h/day and refused from BOOT_COMPLETED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val TAG = "EmbeddedPushService"
        private const val CHANNEL_ID = "embedded-push"
        private const val CHANNEL_NAME = "Background connection"
        private const val CHANNEL_DESCRIPTION =
            "Keeps a connection open so messages arrive without Google Play Services."
        private const val FOREGROUND_ID = 0x5AB1E
        private const val NORMAL_CLOSURE = 1000
        private const val PING_SECONDS = 30L
        private const val WAKELOCK_TAG = "SableEmbeddedPush:WakeLock"
        private const val CONNECT_WAKELOCK_MS = 30_000L
        private const val HEARTBEAT_MS = 900_000L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 120_000L

        fun start(context: Context, replay: Boolean = false) {
            val intent = Intent(context, EmbeddedPushService::class.java).putExtra("replay", replay)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbeddedPushService::class.java))
        }
    }
}
