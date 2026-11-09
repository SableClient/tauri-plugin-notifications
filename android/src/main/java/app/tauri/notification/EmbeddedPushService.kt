package app.tauri.notification

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
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
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
    private val inFlight = mutableSetOf<Push>()
    private val failures = ConcurrentHashMap<Push, Int>()
    private val state by lazy { UnifiedPushStateStore(this) }

    private val connectLock by lazy { newWakeLock(WAKELOCK_TAG) }
    private val processingLock by lazy { newWakeLock(PROCESSING_WAKELOCK_TAG) }

    private data class Subscription(val endpoint: String, val userId: String?, val deviceId: String?)

    private data class Push(val owner: Subscription, val id: String)

    private val connectTimeout = Runnable {
        if (closing || socket == null || ready) return@Runnable
        Log.w(TAG, "Push gateway did not become ready in time")
        record("EMBEDDED_SOCKET_FAILED")
        resetSocket()
        scheduleReconnect()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (closing || network == currentNetwork) return@post
                currentNetwork = network
                val url = EmbeddedPushEndpoint.webSocketUrlForEndpoint(endpoint) ?: return@post
                resetSocket()
                retryDelay = BASE_BACKOFF_MS
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
        live = this
        val manager = getSystemService(ConnectivityManager::class.java)
        currentNetwork = manager.activeNetwork
        manager.registerDefaultNetworkCallback(networkCallback)
        connectivity = manager
    }

    private fun newWakeLock(tag: String): PowerManager.WakeLock =
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }

    private fun cancelReconnect() {
        EmbeddedPushAlarm.cancel(this)
    }

    private fun resetSocket() {
        cancelReconnect()
        handler.removeCallbacks(connectTimeout)
        socket?.cancel()
        socket = null
        ready = false
    }

    private fun record(outcome: String) {
        try {
            PushDelegates.get(this).record(this, outcome)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not record $outcome", error)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tryStartInForeground()
        return syncConnection(intent?.getBooleanExtra(EXTRA_REPLAY, false) == true)
    }

    /** A restarted service is not foreground yet and the app is backgrounded, so promotion can be refused. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        tryStartInForeground()
        syncConnection(replay = false)
        super.onTaskRemoved(rootIntent)
    }

    private fun tryStartInForeground(): Boolean {
        try {
            startInForeground()
            return true
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Foreground start refused; continuing in the background", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Foreground start not permitted; continuing in the background", error)
        }
        return false
    }

    internal fun syncConnection(replay: Boolean): Int {
        val nextEndpoint = state.endpoint
        val url = if (state.isEmbeddedActive) {
            EmbeddedPushEndpoint.webSocketUrlForEndpoint(nextEndpoint)
        } else null
        if (url == null) {
            Log.w(TAG, "No embedded push endpoint to connect to; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        EmbeddedPushWatchdog.ensure(this)
        val nextSubscription = Subscription(nextEndpoint!!, state.pushUserId, state.pushDeviceId)
        if (activeSubscription != nextSubscription) {
            activeSubscription = nextSubscription
            record("EMBEDDED_STARTED")
            resetSocket()
            endpoint = nextEndpoint
            retryDelay = BASE_BACKOFF_MS
        }
        if (replay) resetSocket()
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
        if (live === this) live = null
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered", error)
        }
        connectivity = null
        cancelReconnect()
        handler.removeCallbacks(connectTimeout)
        releaseWakeLock()
        if (processingLock.isHeld) processingLock.release()
        ready = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        pushExecutor.shutdown()
        super.onDestroy()
    }

    private fun connect(url: String) {
        if (!connectLock.isHeld) connectLock.acquire(CONNECT_WAKELOCK_MS)

        val http = client ?: OkHttpClient.Builder()
            // The gateway sends its own keepalives; this catches a half-open socket.
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
            .also { client = it }

        if (!state.isEmbeddedActive || state.endpoint != endpoint) return
        val owner = Subscription(state.endpoint ?: return, state.pushUserId, state.pushDeviceId)
        val requestUrl = Request.Builder().url(url).build().url.newBuilder().addQueryParameter("since", "12h").build()
        socket = http.newWebSocket(Request.Builder().url(requestUrl).build(), Listener(owner))
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
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
        if (connectLock.isHeld) connectLock.release()
    }

    private fun owns(owner: Subscription): Boolean =
        !closing && state.isEmbeddedActive && state.endpoint == owner.endpoint &&
            state.pushUserId == owner.userId && state.pushDeviceId == owner.deviceId

    private fun execute(onFailure: () -> Unit = {}, task: () -> Unit) {
        try {
            pushExecutor.execute {
                try {
                    task()
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Push task failed", error)
                    onFailure()
                }
            }
        } catch (error: RejectedExecutionException) {
            onFailure()
        }
    }

    private fun post(onFailure: () -> Unit = {}, task: () -> Unit) {
        handler.post {
            try {
                task()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Push task failed", error)
                onFailure()
            }
        }
    }

    private fun executePush(push: Push, task: () -> Unit) =
        execute(onFailure = { noteFailure(push) }, task = task)

    private fun postPush(push: Push, task: () -> Unit) =
        post(onFailure = { noteFailure(push) }, task = task)

    private fun release(push: Push) {
        post {
            inFlight.remove(push)
            if (inFlight.isEmpty() && processingLock.isHeld) processingLock.release()
        }
    }

    /** A message that keeps failing is completed, or it replays on every reconnect for a day. */
    private fun noteFailure(push: Push) {
        execute(onFailure = { release(push) }) {
            val attempts = failures.merge(push, 1, Int::plus) ?: 1
            if (attempts >= MAX_ATTEMPTS) {
                failures.remove(push)
                Log.w(TAG, "Giving up on a push that failed $attempts times")
                state.completeEmbeddedPush(push.owner.endpoint, push.id)
            }
            release(push)
        }
    }

    private fun processMessage(owner: Subscription, text: String, json: JSONObject) {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return
        val push = Push(owner, id)
        if (!owns(owner) || !inFlight.add(push)) return
        // Without it the CPU can sleep between the wake-up for the frame and the notification.
        processingLock.acquire(PROCESSING_WAKELOCK_MS)
        val serverTime = json.optLong("time")
        executePush(push) { deliver(push, text, serverTime) }
    }

    private fun deliver(push: Push, text: String, serverTime: Long) {
        val owner = push.owner
        if (!owns(owner) || !state.shouldProcessEmbeddedPush(owner.endpoint, push.id, serverTime)) {
            release(push)
            return
        }
        val sealed = EmbeddedPushEndpoint.pushBody(text)
        if (sealed == null) {
            state.completeEmbeddedPush(owner.endpoint, push.id)
            release(push)
            return
        }
        val opened = decrypt(sealed)
        if (!owns(owner)) {
            release(push)
            return
        }
        if (opened == null) {
            // Retrying cannot change the outcome, so it would only replay on every reconnect.
            state.completeEmbeddedPush(owner.endpoint, push.id)
            release(push)
            return
        }
        val (body, encrypted) = opened
        record("EMBEDDED_DECRYPTED")
        val delegate = PushDelegates.get(this)
        val activation = delegate.isActivation(body)
        try {
            delegate.render(this, body, encrypted)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not display the push notification", error)
            noteFailure(push)
            return
        }
        postPush(push) {
            if (!owns(owner)) {
                release(push)
                return@postPush
            }
            if (activation) delegate.schedule(this, body, encrypted)
            NotificationPlugin.instance?.onUnifiedPushMessage(body, UnifiedPushStateStore.INSTANCE)
            executePush(push) {
                if (owns(owner) && !state.completeEmbeddedPush(owner.endpoint, push.id)) {
                    Log.w(TAG, "Could not persist push completion")
                }
                failures.remove(push)
                release(push)
            }
        }
    }

    private inner class Listener(private val owner: Subscription) : WebSocketListener() {
        private val pending = mutableListOf<Pair<String, JSONObject>>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            post {
                if (closing || socket !== webSocket) return@post
                val json = try {
                    JSONObject(text)
                } catch (error: JSONException) {
                    return@post
                }
                when (json.optString("event")) {
                    "open" -> {
                        execute {
                            val initialized = state.initializeEmbeddedReplay(owner.endpoint, json.optLong("time"))
                            post ready@{
                                if (!owns(owner) || socket !== webSocket) return@ready
                                if (!initialized) {
                                    webSocket.cancel()
                                    return@ready
                                }
                                Log.i(TAG, "Push gateway subscription ready")
                                handler.removeCallbacks(connectTimeout)
                                releaseWakeLock()
                                EmbeddedPushAlarm.schedule(this@EmbeddedPushService, HEARTBEAT_MS)
                                record("EMBEDDED_READY")
                                ready = true
                                retryDelay = BASE_BACKOFF_MS
                                NotificationPlugin.instance?.onEmbeddedPushReady(owner.endpoint)
                                pending.forEach { (frame, payload) -> processMessage(owner, frame, payload) }
                                pending.clear()
                            }
                        }
                    }
                    "message" -> {
                        record("EMBEDDED_MESSAGE_RECEIVED")
                        if (ready) processMessage(owner, text, json) else pending.add(text to json)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            disconnected(webSocket, if (response != null) "EMBEDDED_HTTP_REJECTED" else "EMBEDDED_SOCKET_FAILED")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(webSocket, "EMBEDDED_CLOSED")
        }

        private fun disconnected(webSocket: WebSocket, outcome: String) {
            post {
                if (closing || socket !== webSocket) return@post
                record(outcome)
                resetSocket()
                scheduleReconnect()
            }
        }
    }

    /** The gateway relays the body untouched, so it is still encrypted to our keys. */
    private fun decrypt(sealed: ByteArray): Pair<String, Boolean>? {
        val keysPublished = state.p256dh != null && state.auth != null
        if (keysPublished) {
            EmbeddedWebPushKeys.decrypt(this, sealed)?.let { return String(it) to true }
        } else if (sealed.isNotEmpty() && sealed[0] == '{'.code.toByte()) {
            // Only a registration made without keys expects plaintext. With keys published,
            // anyone who knows the topic could otherwise inject a notification.
            return String(sealed) to false
        }

        record("EMBEDDED_DECRYPT_FAILED")
        Log.w(TAG, "Could not decrypt the push body")
        return null
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                    .apply { description = CHANNEL_DESCRIPTION },
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // `dataSync` would be capped at 6h/day and refused from BOOT_COMPLETED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val TAG = "EmbeddedPushService"
        private const val EXTRA_REPLAY = "replay"
        private const val CHANNEL_ID = "embedded-push"
        private const val CHANNEL_NAME = "Background connection"
        private const val CHANNEL_DESCRIPTION =
            "Keeps a connection open so messages arrive without Google Play Services."
        private const val FOREGROUND_ID = 0x5AB1E
        private const val NORMAL_CLOSURE = 1000
        private const val PING_SECONDS = 30L
        private const val WAKELOCK_TAG = "SableEmbeddedPush:WakeLock"
        private const val PROCESSING_WAKELOCK_TAG = "SableEmbeddedPush:Processing"
        private const val CONNECT_WAKELOCK_MS = 30_000L
        private const val PROCESSING_WAKELOCK_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_MS = 900_000L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val MAX_ATTEMPTS = 3

        @Volatile private var live: EmbeddedPushService? = null

        /** Starting a foreground service from an inexact alarm is refused on Android 12+. */
        internal fun poke(): Boolean {
            val service = live ?: return false
            service.syncConnection(replay = false)
            return true
        }

        /** False when the system refused the start, typically from the background on Android 12+. */
        fun start(context: Context, replay: Boolean = false): Boolean {
            val intent = Intent(context, EmbeddedPushService::class.java).putExtra(EXTRA_REPLAY, replay)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: IllegalStateException) {
                Log.w(TAG, "The system refused to start the push service", error)
                false
            } catch (error: SecurityException) {
                Log.w(TAG, "Not permitted to start the push service", error)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbeddedPushService::class.java))
        }
    }
}
