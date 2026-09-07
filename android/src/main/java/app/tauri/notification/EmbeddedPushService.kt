package app.tauri.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
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
    private var closing = false
    private var retryDelay = BASE_BACKOFF_MS
    private val handler = Handler(Looper.getMainLooper())
    private var endpoint: String? = null
    private var ready = false
    private val pushExecutor = Executors.newSingleThreadExecutor()

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

        if (endpoint != nextEndpoint) {
            handler.removeCallbacksAndMessages(null)
            socket?.cancel()
            socket = null
            ready = false
            endpoint = nextEndpoint
            retryDelay = BASE_BACKOFF_MS
        }
        if (socket == null) {
            handler.removeCallbacksAndMessages(null)
            connect(url)
        } else if (ready) {
            endpoint?.let { NotificationPlugin.instance?.onEmbeddedPushReady(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        closing = true
        handler.removeCallbacksAndMessages(null)
        ready = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        pushExecutor.shutdown()
        super.onDestroy()
    }

    private fun connect(url: String) {
        val http = client ?: OkHttpClient.Builder()
            // The gateway sends its own keepalives; this catches a half-open socket.
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
            .also { client = it }

        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(url))
    }

    private fun scheduleReconnect(url: String) {
        if (closing) return
        val delay = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(MAX_BACKOFF_MS)
        Log.i(TAG, "Reconnecting to the push gateway in ${delay}ms")
        handler.postDelayed({ if (!closing && socket == null) connect(url) }, delay)
    }

    private inner class Listener(private val url: String) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            handler.post {
                if (closing || socket !== webSocket) return@post
                val event = try {
                    JSONObject(text).optString("event")
                } catch (_: Exception) {
                    return@post
                }
                if (event == "open") {
                    Log.i(TAG, "Push gateway subscription ready")
                    ready = true
                    retryDelay = BASE_BACKOFF_MS
                    endpoint?.let { NotificationPlugin.instance?.onEmbeddedPushReady(it) }
                    return@post
                }
                val sealed = EmbeddedPushEndpoint.pushBody(text) ?: return@post
                pushExecutor.execute {
                    val body = decrypt(sealed) ?: return@execute
                    runCatching { UnifiedPushNotifier.showFromPush(this@EmbeddedPushService, body) }
                        .onFailure { Log.w(TAG, "Could not display the push notification") }
                    handler.post {
                        if (!closing && socket === webSocket) {
                            NotificationPlugin.instance?.onUnifiedPushMessage(body, UnifiedPushStateStore.INSTANCE)
                        }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            disconnected(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(webSocket)
        }

        private fun disconnected(webSocket: WebSocket) {
            handler.post {
                if (closing || socket !== webSocket) return@post
                socket = null
                ready = false
                scheduleReconnect(url)
            }
        }
    }

    /** The gateway relays the body untouched, so it is still encrypted to our keys. */
    private fun decrypt(sealed: ByteArray): String? {
        EmbeddedWebPushKeys.decrypt(this, sealed)?.let { return String(it) }

        // A plaintext relay is used for testing; not a failure worth logging.
        if (sealed.isNotEmpty() && sealed[0] == '{'.code.toByte()) return String(sealed)

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
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 300_000L

        fun start(context: Context) {
            val intent = Intent(context, EmbeddedPushService::class.java)
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
