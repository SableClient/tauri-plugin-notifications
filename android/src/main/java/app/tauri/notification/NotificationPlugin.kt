package app.tauri.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.Logger
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.google.firebase.messaging.FirebaseMessaging
import org.unifiedpush.android.connector.UnifiedPush
import java.util.ArrayDeque
import java.util.UUID

const val LOCAL_NOTIFICATIONS = "permissionState"
private const val MAX_PENDING_ACTIONS = 32
private const val PUSH_REGISTRATION_TIMEOUT_MS = 30_000L

@InvokeArg
class PluginConfig {
  var icon: String? = null
  var sound: String? = null
  var iconColor: String? = null
  var actionTypes: List<ActionType>? = null
}

@InvokeArg
class BatchArgs {
  lateinit var notifications: List<Notification>
}

@InvokeArg
class CancelArgs {
  lateinit var notifications: List<Int>
}

@InvokeArg
class NotificationAction {
  lateinit var id: String
  var title: String? = null
  var input: Boolean? = null
}

@InvokeArg
class ActionType {
  lateinit var id: String
  lateinit var actions: List<NotificationAction>
}

@InvokeArg
class RegisterActionTypesArgs {
  lateinit var types: List<ActionType>
}

@InvokeArg
class RegisterPushArgs {
  var vapid: String? = null
  var provider: String? = null
}

@InvokeArg
class SetClickListenerActiveArgs {
  var active: Boolean = false
}

@InvokeArg
class SetActionListenerActiveArgs {
  var active: Boolean = false
}

@InvokeArg
class DistributorArgs {
  var distributor: String? = null
}

@InvokeArg
class ActiveNotification {
  var id: Int = 0
  var tag: String? = null
}

@InvokeArg
class RemoveActiveArgs {
  var notifications: List<ActiveNotification> = listOf()
}

@TauriPlugin(
  permissions = [
    Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "permissionState")
  ]
)
class NotificationPlugin(private val activity: Activity): Plugin(activity) {
  private var webView: WebView? = null
  private lateinit var manager: TauriNotificationManager
  private lateinit var notificationManager: NotificationManager
  private lateinit var notificationStorage: NotificationStorage
  private var channelManager = ChannelManager(activity)

  private data class PushRegistration(
    val vapid: String?,
    val provider: String,
    val instance: String?,
    var distributor: String?,
    var phase: PushRegistrationPhase,
    val invoke: Invoke,
    val generation: Long,
    var timeout: Runnable? = null
  )

  private enum class PushRegistrationPhase { PERMISSION, DISTRIBUTOR, UNIFIED_PUSH, FCM }

  private var pendingPushRegistration: PushRegistration? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var fcmToken: String? = null
  private val unifiedPushState = UnifiedPushStateStore(activity)
  private var unifiedPushGeneration = 0L


  // Click listener tracking for cold-start support
  private var hasClickedListener = false
  private var pendingNotificationClick: JSObject? = null
  private var hasActionListener = false
  private val pendingNotificationActions = ArrayDeque<JSObject>()

  // onNewIntent can fire before load() during a cold start triggered
  // by a notification tap (Android delivers the launch intent via
  // both onCreate's activity.intent AND onNewIntent in certain launch
  // modes; if Tauri's PluginManager hasn't called load() yet,
  // `manager` is uninitialized and the original code crashed with
  // `lateinit property manager has not been initialized`). Buffer the
  // intent and drain in load() instead.
  private var pendingIntent: Intent? = null

  companion object {
    var instance: NotificationPlugin? = null

    fun triggerNotification(notification: Notification, source: String = "local") {
      val data = JSObject()
      data.put("source", source)
      data.put("id", notification.id)
      notification.title?.let { data.put("title", it) }
      notification.body?.let { data.put("body", it) }
      notification.largeBody?.let { data.put("largeBody", it) }
      notification.summary?.let { data.put("summary", it) }
      notification.sound?.let { data.put("sound", it) }
      notification.actionTypeId?.let { data.put("actionTypeId", it) }
      notification.group?.let { data.put("group", it) }
      notification.channelId?.let { data.put("channelId", it) }
      if (notification.isGroupSummary) data.put("groupSummary", true)
      if (notification.isOngoing) data.put("ongoing", true)
      if (notification.isAutoCancel) data.put("autoCancel", true)
      notification.silent?.let { data.put("silent", it) }
      notification.extra?.let { data.put("extra", it) }
      notification.inboxLines?.let { data.put("inboxLines", JSArray(it)) }
      notification.attachments?.let { attachments ->
        val arr = JSArray()
        for (att in attachments) {
          val obj = JSObject()
          att.id?.let { obj.put("id", it) }
          att.url?.let { obj.put("url", it) }
          arr.put(obj)
        }
        data.put("attachments", arr)
      }
      instance?.trigger("notification", data)
    }
  }

  override fun load(webView: WebView) {
    instance = this

    super.load(webView)
    this.webView = webView
    notificationStorage = NotificationStorage(activity, jsonMapper())
    
    val manager = TauriNotificationManager(
      notificationStorage,
      activity,
      activity,
      getConfig(PluginConfig::class.java)
    )
    manager.createNotificationChannel()
    
    this.manager = manager
    getConfig(PluginConfig::class.java)?.actionTypes?.let { notificationStorage.writeActionGroup(it) }
    
    notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val intent = activity.intent
    intent?.let {
      onIntent(it)
    }
    // Drain any intent that arrived via onNewIntent before load() ran.
    pendingIntent?.let {
      pendingIntent = null
      // Skip if onIntent(activity.intent) above already handled the
      // same intent — comparing by reference is the cheapest dedup
      // (Tauri's TauriActivity calls setIntent() in onNewIntent, so
      // activity.intent points at the same Intent instance).
      if (it !== intent) onIntent(it)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (!::manager.isInitialized) {
      Logger.debug(
        Logger.tags(TAG),
        "onNewIntent fired before plugin load(); buffering until init"
      )
      pendingIntent = intent
      return
    }
    onIntent(intent)
  }

  override fun onDestroy() {
    pendingPushRegistration?.let { registration ->
      if (registration.phase == PushRegistrationPhase.UNIFIED_PUSH ||
        registration.phase == PushRegistrationPhase.DISTRIBUTOR) retireUnifiedPush(registration.instance)
    }
    finishPushRegistrationError("Notification plugin destroyed during push registration")
    if (instance === this) instance = null
    super.onDestroy()
  }

  fun onIntent(intent: Intent) {
    Logger.debug(Logger.tags(TAG), "onIntent called - action: ${intent.action}, extras: ${intent.extras?.keySet()}")

    // Handle local notification click (requires ACTION_MAIN)
    if (Intent.ACTION_MAIN == intent.action) {
      val dataJson = manager.handleNotificationActionPerformed(intent, notificationStorage)
      if (dataJson != null) {
        when (dataJson.getString("actionId")) {
          DEFAULT_PRESS_ACTION -> {
            triggerActionPerformed(dataJson)
            triggerNotificationClicked(
              intent.getIntExtra(NOTIFICATION_INTENT_KEY, -1),
              extractLocalNotificationData(intent)
            )
          }
          "dismiss" -> triggerActionPerformed(dataJson)
          else -> triggerActionPerformed(dataJson)
        }
        return
      }
    }

    // Handle push notification click (Firebase background notification)
    // Firebase may use different actions, so check for push data regardless of action
    val pushData = extractPushNotificationData(intent)
    if (pushData != null) {
      Logger.debug(Logger.tags(TAG), "Push notification clicked with data: $pushData")
      triggerNotificationClicked(-1, pushData)
    }
  }

  private fun extractLocalNotificationData(intent: Intent): JSObject? {
    val notificationJson = intent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY) ?: return null
    return try {
      val notification = JSObject(notificationJson)
      if (notification.has("extra")) notification.getJSObject("extra") else null
    } catch (e: Exception) {
      Logger.error(Logger.tags(TAG), "Failed to extract local notification data: ${e.message}", e)
      null
    }
  }

  private fun extractPushNotificationData(intent: Intent): JSObject? {
    val extras = intent.extras ?: return null
    // Skip if no extras or if it's a regular app launch
    if (extras.isEmpty) return null

    Logger.debug(Logger.tags(TAG), "extractPushNotificationData - all extras: ${extras.keySet().map { "$it=${extras.getString(it)}" }}")

    // Filter out system/internal keys, keep user data
    val data = JSObject()
    for (key in extras.keySet()) {
      // Skip Android/Firebase internal keys
      if (key.startsWith("android.") || key.startsWith("google.") ||
          key.startsWith("gcm.") || key == "from" || key == "collapse_key") continue
      extras.getString(key)?.let { data.put(key, it) }
    }
    Logger.debug(Logger.tags(TAG), "extractPushNotificationData - filtered data length: ${data.length()}")
    return if (data.length() > 0) data else null
  }

  private fun triggerNotificationClicked(id: Int, data: JSObject?) {
    val clickedData = JSObject()
    clickedData.put("id", id)
    if (data != null) {
      clickedData.put("data", data)
    }

    Logger.debug(Logger.tags(TAG), "triggerNotificationClicked - id: $id, hasClickedListener: $hasClickedListener, data: $data")

    if (hasClickedListener) {
      trigger("notificationClicked", clickedData)
    } else {
      Logger.debug(Logger.tags(TAG), "No click listener, storing as pending")
      pendingNotificationClick = clickedData
    }
  }

  private fun triggerActionPerformed(data: JSObject) {
    if (hasActionListener) {
      trigger("actionPerformed", data)
    } else {
      if (pendingNotificationActions.size >= MAX_PENDING_ACTIONS) pendingNotificationActions.removeFirst()
      pendingNotificationActions.add(data)
    }
  }

  @Command
  fun show(invoke: Invoke) {
    val notification = invoke.parseArgs(Notification::class.java)
    notification.sourceJson = invoke.getRawArgs()

    val id = manager.schedule(notification)
    if (notification.schedule != null) {
      notificationStorage.appendNotifications(listOf(notification))
    }

    invoke.resolveObject(id)
  }

  @Command
  fun batch(invoke: Invoke) {
    val args = invoke.parseArgs(BatchArgs::class.java)
    val mapper = jsonMapper()
    for (notification in args.notifications) {
      notification.sourceJson = mapper.writeValueAsString(notification)
    }

    val ids = manager.schedule(args.notifications)
    notificationStorage.appendNotifications(args.notifications)

    invoke.resolveObject(ids)
  }

  @Command
  fun cancel(invoke: Invoke) {
    val args = invoke.parseArgs(CancelArgs::class.java)
    manager.cancel(args.notifications)
    invoke.resolve()
  }

  @Command
  fun cancelAll(invoke: Invoke) {
    val ids = notificationStorage.getSavedNotificationIds().mapNotNull { it.toIntOrNull() }
    manager.cancel(ids)
    invoke.resolve()
  }

  @Command
  fun removeActive(invoke: Invoke) {
    val args = invoke.parseArgs(RemoveActiveArgs::class.java)

    if (args.notifications.isEmpty()) {
      notificationManager.cancelAll()
      invoke.resolve()
    } else {
      for (notification in args.notifications) {
        if (notification.tag == null) {
          notificationManager.cancel(notification.id)
        } else {
          notificationManager.cancel(notification.tag, notification.id)
        }
      }
      invoke.resolve()
    }
  }

  @Command
  fun getPending(invoke: Invoke) {
    val notifications = notificationStorage.getSavedNotifications()
    val result = Notification.buildNotificationPendingList(notifications)
    invoke.resolveObject(result)
  }

  @Command
  fun registerActionTypes(invoke: Invoke) {
    val args = invoke.parseArgs(RegisterActionTypesArgs::class.java)
    notificationStorage.writeActionGroup(args.types)
    invoke.resolve()
  }

  @SuppressLint("ObsoleteSdkInt")
  @Command
  fun getActive(invoke: Invoke) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val result = Notification.buildNotificationActiveList(notificationManager.activeNotifications)
      invoke.resolveObject(result)
    } else {
      invoke.resolveObject(emptyList<ActiveNotificationInfo>())
    }
  }

  @Command
  fun createChannel(invoke: Invoke) {
    channelManager.createChannel(invoke)
  }

  @Command
  fun deleteChannel(invoke: Invoke) {
    channelManager.deleteChannel(invoke)
  }

  @Command
  fun listChannels(invoke: Invoke) {
    channelManager.listChannels(invoke)
  }

  @Command
  override fun checkPermissions(invoke: Invoke) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      val permissionsResultJSON = JSObject()
      permissionsResultJSON.put("permissionState", getPermissionState())
      invoke.resolve(permissionsResultJSON)
    } else {
      super.checkPermissions(invoke)
    }
  }

  @Command
  override fun requestPermissions(invoke: Invoke) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      permissionState(invoke)
    } else {
      if (getPermissionState(LOCAL_NOTIFICATIONS) !== PermissionState.GRANTED) {
        requestPermissionForAlias(LOCAL_NOTIFICATIONS, invoke, "permissionsCallback")
      }
    }
  }

  @Command
  fun registerForPushNotifications(invoke: Invoke) {
    if (!BuildConfig.ENABLE_PUSH_NOTIFICATIONS) {
      invoke.reject("Push notifications are disabled in this build")
      return
    }

    val args = invoke.parseArgs(RegisterPushArgs::class.java)
    val requestedVapid = args.vapid
    val provider = args.provider ?: "auto"
    if (provider !in setOf("auto", "fcm", "unifiedpush")) {
      invoke.reject("Unknown push provider: $provider")
      return
    }
    pendingPushRegistration?.let { registration ->
      invoke.reject("Push registration already in progress")
      return
    }
    if (provider == "fcm" && unifiedPushState.activeProvider == "unifiedpush") {
      invoke.reject("Active UnifiedPush registration must be unregistered first")
      return
    }
    if (provider == "unifiedpush" && unifiedPushState.activeProvider == "fcm") {
      invoke.reject("Active FCM registration must be unregistered first")
      return
    }
    val distributor = if (provider == "fcm") null else UnifiedPush.getSavedDistributor(activity)
    pendingPushRegistration = PushRegistration(
      requestedVapid,
      provider,
      if (provider == "fcm") null else "sable-registration-${UUID.randomUUID()}",
      distributor,
      PushRegistrationPhase.PERMISSION,
      invoke,
      ++unifiedPushGeneration
    )
    schedulePushRegistrationTimeout()

    // First check if notifications are enabled
    if (!manager.areNotificationsEnabled()) {
      // Request permissions first
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (getPermissionState(LOCAL_NOTIFICATIONS) !== PermissionState.GRANTED) {
          try {
            requestPermissionForAlias(LOCAL_NOTIFICATIONS, invoke, "pushPermissionsCallback")
          } catch (error: Exception) {
            finishPushRegistrationError(error.message ?: "Failed to request notification permissions")
          }
          return
        }
      } else {
        finishPushRegistrationError("Notification permissions not granted")
        return
      }
    }

    proceedPushRegistration()
  }

  private fun proceedPushRegistration() {
    val registration = pendingPushRegistration ?: return
    val webPushVapid = registration.vapid
    if (registration.provider != "fcm" && registration.distributor != null) {
      registration.phase = PushRegistrationPhase.UNIFIED_PUSH
      try {
        UnifiedPush.register(activity, registration.instance!!, vapid = webPushVapid)
      } catch (error: Exception) {
        finishPushRegistrationError(error.message ?: "UnifiedPush registration failed")
      }
      return
    }

    if (webPushVapid != null && (registration.provider == "unifiedpush" || registration.provider == "auto")) {
      registration.phase = PushRegistrationPhase.DISTRIBUTOR
      try {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
          if (pendingPushRegistration !== registration) return@tryUseCurrentOrDefaultDistributor
          if (!success) {
            finishPushRegistrationError("No UnifiedPush distributor available")
            return@tryUseCurrentOrDefaultDistributor
          }
          try {
            registration.distributor = UnifiedPush.getSavedDistributor(activity)
            registration.phase = PushRegistrationPhase.UNIFIED_PUSH
            UnifiedPush.register(activity, registration.instance!!, vapid = webPushVapid)
          } catch (error: Exception) {
            finishPushRegistrationError(error.message ?: "UnifiedPush registration failed")
          }
        }
      } catch (error: Exception) {
        finishPushRegistrationError(error.message ?: "UnifiedPush registration failed")
      }
      return
    }

    if (registration.provider != "fcm" && UnifiedPush.getSavedDistributor(activity) != null) {
      registration.phase = PushRegistrationPhase.UNIFIED_PUSH
      try {
        UnifiedPush.register(activity, registration.instance!!)
      } catch (error: Exception) {
        finishPushRegistrationError(error.message ?: "UnifiedPush registration failed")
      }
      return
    }

    if (registration.provider == "unifiedpush") {
      finishPushRegistrationError("No UnifiedPush distributor available")
      return
    }

    fcmToken?.let {
      unifiedPushState.activeProvider = "fcm"
      val result = JSObject()
      result.put("deviceToken", it)
      finishPushRegistrationSuccess(result)
      return
    }

    registration.phase = PushRegistrationPhase.FCM
    getFirebaseToken(registration)
  }

  @Command
  fun unregisterForPushNotifications(invoke: Invoke) {
    if (!BuildConfig.ENABLE_PUSH_NOTIFICATIONS) {
      invoke.reject("Push notifications are disabled in this build")
      return
    }

    val pendingUnifiedPush = pendingPushRegistration?.takeIf {
      it.phase == PushRegistrationPhase.UNIFIED_PUSH || it.phase == PushRegistrationPhase.DISTRIBUTOR
    }
    val instanceToUnregister = pendingUnifiedPush?.instance ?: unifiedPushState.activeInstance
    finishPushRegistrationError("Push registration cancelled by unregister")

    if (pendingUnifiedPush != null || unifiedPushState.activeProvider == "unifiedpush") {
      try {
        retireUnifiedPush(instanceToUnregister)
      } catch (error: Exception) {
        invoke.reject(error.message ?: "Failed to unregister UnifiedPush")
        return
      }
      unifiedPushState.activeProvider = null
      invoke.resolve()
      return
    }

    FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
      if (!task.isSuccessful) {
        invoke.reject("Failed to delete FCM token: ${task.exception?.message}")
        return@addOnCompleteListener
      }
      fcmToken = null
      if (unifiedPushState.activeProvider == "fcm") unifiedPushState.activeProvider = null
      invoke.resolve()
    }
  }

  @PermissionCallback
  private fun pushPermissionsCallback(invoke: Invoke) {
    val registration = pendingPushRegistration
    if (registration?.phase != PushRegistrationPhase.PERMISSION) {
      return
    }
    if (!manager.areNotificationsEnabled()) {
      finishPushRegistrationError("Notification permissions denied")
      return
    }

    proceedPushRegistration()
  }

  @Command
  fun listDistributors(invoke: Invoke) {
    val distributors = UnifiedPush.getDistributors(activity)
    val result = JSObject()
    val arr = JSArray()
    distributors.forEach { arr.put(it) }
    result.put("distributors", arr)
    invoke.resolve(result)
  }

  @Command
  fun setDistributor(invoke: Invoke) {
    val args = invoke.parseArgs(DistributorArgs::class.java)
    val distributor = args.distributor
    if (distributor == null) {
      invoke.reject("Distributor parameter is required")
      return
    }
    if (pendingPushRegistration != null) {
      invoke.reject("Cannot change distributor while push registration is in progress")
      return
    }
    if (UnifiedPush.getSavedDistributor(activity) != distributor) {
      try { retireUnifiedPush(unifiedPushState.activeInstance) } catch (error: Exception) {
        invoke.reject(error.message ?: "Failed to retire UnifiedPush registration")
        return
      }
      if (unifiedPushState.activeProvider == "unifiedpush") unifiedPushState.activeProvider = null
    }
    UnifiedPush.saveDistributor(activity, distributor)
    invoke.resolve()
  }

  @Command
  fun setToken(invoke: Invoke) {
    invoke.resolve()
  }

  fun onUnifiedPushNewEndpoint(endpoint: String, p256dh: String?, auth: String?, instance: String) {
    val registration = pendingPushRegistration
    if (registration?.phase != PushRegistrationPhase.UNIFIED_PUSH ||
      registration.generation != unifiedPushGeneration || instance != registration.instance) {
      return
    }
    unifiedPushState.setUnifiedPushActive()
    unifiedPushState.activeInstance = registration.instance
    val result = JSObject()
    result.put("deviceToken", endpoint)
    result.put("instance", UnifiedPushStateStore.INSTANCE)
    p256dh?.let { result.put("p256dh", it) }
    auth?.let { result.put("auth", it) }
    finishPushRegistrationSuccess(result)

    triggerUnifiedPushToken(endpoint, p256dh, auth, if (registration.vapid == null) "direct" else "webpush")
  }

  fun onUnifiedPushRegistrationFailed(reason: String?, instance: String) {
    val registration = pendingPushRegistration
    if (registration?.phase != PushRegistrationPhase.UNIFIED_PUSH ||
      registration.generation != unifiedPushGeneration || instance != registration.instance) return
    finishPushRegistrationError(reason ?: "UnifiedPush registration failed")
  }

  fun onUnifiedPushUnregistered(instance: String) {
  }

  fun onUnifiedPushTemporaryUnavailable(instance: String) {
    // Temporary distributor loss is nonterminal; endpoint/failure/timeout settles registration.
  }

  fun onNotificationDismissed(id: Int) {
    val notification = JSObject()
    notification.put("id", id)
    val action = JSObject()
    action.put("actionId", "dismiss")
    action.put("notification", notification)
    triggerActionPerformed(action)
  }

  fun onUnifiedPushMessage(content: String, instance: String) {
    if (instance != unifiedPushState.activeInstance || unifiedPushState.activeProvider != "unifiedpush") return
    val data = JSObject()
    data.put("message", content)
    data.put("transport", "unifiedpush")
    data.put("instance", "default")
    trigger("push-message", data)
  }

  private fun triggerUnifiedPushToken(endpoint: String, p256dh: String?, auth: String?, mode: String) {
    val data = JSObject()
    data.put("token", endpoint)
    data.put("provider", "unifiedpush")
    data.put("instance", UnifiedPushStateStore.INSTANCE)
    data.put("mode", mode)
    p256dh?.let { data.put("p256dh", it) }
    auth?.let { data.put("auth", it) }
    trigger("push-token", data)
  }

  private fun getFirebaseToken(registration: PushRegistration) {
    if (!BuildConfig.ENABLE_PUSH_NOTIFICATIONS) {
      finishPushRegistrationError("Push notifications are disabled in this build")
      return
    }

    try {
      FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (pendingPushRegistration !== registration || registration.phase != PushRegistrationPhase.FCM) {
          return@addOnCompleteListener
        }
        if (!task.isSuccessful) {
          val errorMessage = "Failed to get FCM token: ${task.exception?.message}"
          val errorData = JSObject()
          errorData.put("message", errorMessage)
          trigger("push-error", errorData)
          finishPushRegistrationError(errorMessage)
          return@addOnCompleteListener
        }

        val token = task.result
        fcmToken = token
        unifiedPushState.activeProvider = "fcm"
        val result = JSObject()
        result.put("deviceToken", token)
        finishPushRegistrationSuccess(result)
      }
    } catch (error: Exception) {
      finishPushRegistrationError(error.message ?: "Failed to get FCM token")
    }
  }

  private fun finishPushRegistrationSuccess(result: JSObject) {
    val registration = pendingPushRegistration ?: return
    pendingPushRegistration = null
    registration.timeout?.let { mainHandler.removeCallbacks(it) }
    registration.invoke.resolve(result)
  }

  private fun finishPushRegistrationError(message: String) {
    val registration = pendingPushRegistration ?: return
    pendingPushRegistration = null
    registration.timeout?.let { mainHandler.removeCallbacks(it) }
    registration.invoke.reject(message)
  }

  private fun schedulePushRegistrationTimeout() {
    val registration = pendingPushRegistration ?: return
    val timeout = Runnable {
      if (pendingPushRegistration === registration) {
        if (registration.phase == PushRegistrationPhase.UNIFIED_PUSH || registration.phase == PushRegistrationPhase.DISTRIBUTOR) {
          retireUnifiedPush(registration.instance)
        }
        finishPushRegistrationError("Timed out registering for push notifications")
      }
    }
    registration.timeout = timeout
    mainHandler.postDelayed(timeout, PUSH_REGISTRATION_TIMEOUT_MS)
  }

  private fun retireUnifiedPush(instance: String?) {
    unifiedPushGeneration++
    try { UnifiedPush.unregister(activity, instance ?: UnifiedPushStateStore.INSTANCE) } catch (_: Exception) {
    }
  }

  // Called by TauriFirebaseMessagingService when a new token is received
  fun handleNewToken(token: String) {
    if (!BuildConfig.ENABLE_PUSH_NOTIFICATIONS) return

    if (unifiedPushState.activeProvider != "fcm") return
    fcmToken = token
    // Trigger push-token event to notify the frontend about the token
    val data = JSObject()
    data.put("token", token)
    trigger("push-token", data)
  }

  // Called by TauriFirebaseMessagingService when a push message is received
  fun triggerPushMessage(pushData: Map<String, Any>) {
    if (!BuildConfig.ENABLE_PUSH_NOTIFICATIONS) return
    if (unifiedPushState.activeProvider != "fcm") return

    val data = JSObject()
    for ((key, value) in pushData) {
      when (value) {
        is String -> data.put(key, value)
        is Int -> data.put(key, value)
        is Long -> data.put(key, value)
        is Double -> data.put(key, value)
        is Boolean -> data.put(key, value)
        is Map<*, *> -> {
          val nestedObj = JSObject()
          @Suppress("UNCHECKED_CAST")
          val map = value as Map<String, Any>
          for ((k, v) in map) {
            nestedObj.put(k, v.toString())
          }
          data.put(key, nestedObj)
        }
        else -> data.put(key, value.toString())
      }
    }
    trigger("push-message", data)
  }

  @Command
  fun permissionState(invoke: Invoke) {
    val permissionsResultJSON = JSObject()
    permissionsResultJSON.put("permissionState", getPermissionState())
    invoke.resolve(permissionsResultJSON)
  }

  @PermissionCallback
  private fun permissionsCallback(invoke: Invoke) {
    val permissionsResultJSON = JSObject()
    permissionsResultJSON.put("permissionState", getPermissionState())
    invoke.resolve(permissionsResultJSON)
  }

  private fun getPermissionState(): String {
    return if (manager.areNotificationsEnabled()) {
      "granted"
    } else {
      "denied"
    }
  }

  @Command
  fun setClickListenerActive(invoke: Invoke) {
    val args = invoke.parseArgs(SetClickListenerActiveArgs::class.java)
    hasClickedListener = args.active

    // If listener just became active and we have pending click, trigger it
    if (args.active && pendingNotificationClick != null) {
      trigger("notificationClicked", pendingNotificationClick!!)
      pendingNotificationClick = null
    }

    invoke.resolve()
  }

  @Command
  fun setActionListenerActive(invoke: Invoke) {
    val args = invoke.parseArgs(SetActionListenerActiveArgs::class.java)
    hasActionListener = args.active

    if (args.active) {
      while (pendingNotificationActions.isNotEmpty()) {
        trigger("actionPerformed", pendingNotificationActions.removeFirst())
      }
    }

    invoke.resolve()
  }
}
