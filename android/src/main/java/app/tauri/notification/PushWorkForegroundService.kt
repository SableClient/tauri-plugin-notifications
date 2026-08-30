package app.tauri.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.tauri.Logger

class PushWorkForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var inForeground = false

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationIcon(this))
            .setContentTitle(SYNCING_TITLE)
            .setProgress(0, 0, true)
            .setSilent(true)
            .setOngoing(true)
            .build()

        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType())
        }.isSuccess

        inForeground = started
        if (started) {
            runCatching { wakeLock.acquire(WAKELOCK_TIMEOUT_MS) }
        } else {
            Logger.warn(Logger.tags(TAG), "Not allowed to start a foreground service from the background")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payload = intent?.getStringExtra(EXTRA_PAYLOAD)
        if (payload == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!inForeground) stopSelf(startId)

        val context = applicationContext
        Thread {
            runCatching { UnifiedPushNotifier.showFromPush(context, payload) }
                .onFailure { Logger.error(Logger.tags(TAG), "Cold push rendering failed", it as? Exception) }
            if (inForeground) stopSelf(startId)
        }.start()

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.warn(Logger.tags(TAG), "Cold push work exceeded the shortService budget")
        stopSelf(startId)
    }

    override fun onDestroy() {
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        super.onDestroy()
    }

    private fun serviceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }

    companion object {
        private const val TAG = "PushWorkForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "push.work"
        private const val SYNCING_TITLE = "Checking for new messages"
        private const val WAKELOCK_TAG = "SablePushWork:WakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 3L * 60L * 1000L
        private const val EXTRA_PAYLOAD = "payload"

        fun render(context: Context, payload: String): Boolean {
            val intent = Intent(context, PushWorkForegroundService::class.java)
                .putExtra(EXTRA_PAYLOAD, payload)
            return runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
        }

        private fun notificationIcon(context: Context): Int =
            context.resources
                .getIdentifier("notification_icon", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        private fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannelsCompat(
                listOf(
                    NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                        .setName(SYNCING_TITLE)
                        .setVibrationEnabled(false)
                        .setSound(null, null)
                        .build()
                )
            )
        }
    }
}
