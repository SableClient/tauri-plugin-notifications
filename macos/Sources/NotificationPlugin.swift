import AppKit
import UserNotifications

extension FFIResult: Error {}

typealias JsonObject = [String: Any]

enum ScheduleEveryKind: String, Codable {
  case year
  case month
  case twoWeeks
  case week
  case day
  case hour
  case minute
  case second
}

struct ScheduleInterval: Codable {
  var year: Int?
  var month: Int?
  var day: Int?
  var weekday: Int?
  var hour: Int?
  var minute: Int?
  var second: Int?
}

enum NotificationSchedule: Codable {
  case at(date: String, repeating: Bool)
  case interval(interval: ScheduleInterval)
  case every(interval: ScheduleEveryKind, count: Int)
}

struct NotificationAttachmentOptions: Codable {
  let iosUNNotificationAttachmentOptionsTypeHintKey: String?
  let iosUNNotificationAttachmentOptionsThumbnailHiddenKey: String?
  let iosUNNotificationAttachmentOptionsThumbnailClippingRectKey: String?
  let iosUNNotificationAttachmentOptionsThumbnailTimeKey: String?
}

struct NotificationAttachment: Codable {
  let id: String
  let url: String
  let options: NotificationAttachmentOptions?
}

struct Notification: Decodable {
  let id: Int
  var title: String
  var body: String?
  var extra: [String: String]?
  var schedule: NotificationSchedule?
  var attachments: [NotificationAttachment]?
  var sound: String?
  var group: String?
  var actionTypeId: String?
  var summary: String?
  var silent: Bool?
}

struct CancelArgs: Decodable {
  let notifications: [Int]
}

struct Action: Decodable {
  let id: String
  let title: String
  var requiresAuthentication: Bool?
  var foreground: Bool?
  var destructive: Bool?
  var input: Bool?
  var inputButtonTitle: String?
  var inputPlaceholder: String?
}

struct ActionType: Decodable {
  let id: String
  let actions: [Action]
  var hiddenPreviewsBodyPlaceholder: String?
  var customDismissAction: Bool?
  var allowInCarPlay: Bool?
  var hiddenPreviewsShowTitle: Bool?
  var hiddenPreviewsShowSubtitle: Bool?
  var hiddenBodyPlaceholder: String?
}

struct RegisterActionTypesArgs: Decodable {
  let types: [ActionType]
}

struct SetClickListenerActiveArgs: Decodable {
  let active: Bool
}

struct SetActionListenerActiveArgs: Decodable {
  let active: Bool
}

struct RemoveActiveNotification: Decodable {
  let id: Int
}

struct RemoveActiveArgs: Decodable {
  let notifications: [RemoveActiveNotification]
}

extension RustString {
  func decode<T: Decodable>(_ type: T.Type) throws(FFIResult) -> T {
    guard let data = self.toString().data(using: .utf8) else {
      throw FFIResult.Err(RustString("Invalid UTF-8 string"))
    }
    do {
      return try JSONDecoder().decode(type, from: data)
    } catch {
      throw FFIResult.Err(RustString("Failed to decode JSON: \(error.localizedDescription)"))
    }
  }
}

extension Encodable {
  func toJSONString() throws(FFIResult) -> String {
    do {
      let jsonData = try JSONEncoder().encode(self)
      guard let jsonString = String(data: jsonData, encoding: .utf8) else {
        throw FFIResult.Err(RustString("Failed to encode to JSON string"))
      }
      return jsonString
    } catch let error as FFIResult {
      throw error
    } catch {
      throw FFIResult.Err(RustString("Failed to encode to JSON: \(error.localizedDescription)"))
    }
  }
}

func showNotification(notification: Notification) async throws(FFIResult) -> UNNotificationRequest {
  var content: UNNotificationContent
  do {
    content = try makeNotificationContent(notification)
  } catch {
    throw FFIResult.Err(RustString(error.localizedDescription))
  }

  var trigger: UNNotificationTrigger?

  do {
    if let schedule = notification.schedule {
      try trigger = handleScheduledNotification(schedule)
    }
  } catch {
    throw FFIResult.Err(RustString(error.localizedDescription))
  }

  // Schedule the request.
  let request = UNNotificationRequest(
    identifier: "\(notification.id)", content: content, trigger: trigger
  )

  let center = UNUserNotificationCenter.current()
  do {
    try await center.add(request)
  } catch {
    throw FFIResult.Err(RustString(error.localizedDescription))
  }

  return request
}

class NotificationPlugin {
  let notificationHandler = NotificationHandler()
  let notificationManager = NotificationManager()

  #if ENABLE_PUSH_NOTIFICATIONS
    // Push registration state is only touched on the main queue.
    private var pushTokenCompletions = [(Result<String, Error>) -> Void]()
    private let pushTokenTimeout: TimeInterval = 10.0
    private var pushTokenTimer: Timer?
  #endif

  init() {
    #if ENABLE_PUSH_NOTIFICATIONS
      // Store reference to this plugin for event triggering
      AppDelegateSwizzler.plugin = self

      // swizzle UIApplicationDelegate push methods
      AppDelegateSwizzler.swizzlePushCallbacks()
    #endif

    notificationHandler.plugin = self
    notificationManager.notificationHandler = notificationHandler
  }

  public func show(args: RustString) async throws(FFIResult) -> Int32 {
    let notification = try args.decode(Notification.self)

    let request = try await showNotification(notification: notification)
    notificationHandler.saveNotification(request.identifier, notification)
    return Int32(request.identifier) ?? -1
  }

  public func requestPermissions() async throws(FFIResult) -> String {
    do {
      let granted = try await notificationHandler.requestPermissions()
      let permissionState = granted ? "granted" : "denied"
      return "{\"permissionState\":\"\(permissionState)\"}"
    } catch {
      throw FFIResult.Err(RustString(error.localizedDescription))
    }
  }

  public func registerForPushNotifications() async throws(FFIResult) -> String {
    #if ENABLE_PUSH_NOTIFICATIONS
      let granted: Bool
      do {
        granted = try await notificationHandler.ensureDeliveryAllowed()
      } catch {
        throw FFIResult.Err(RustString("Failed to request notification permissions: \(error.localizedDescription)"))
      }

      guard granted else {
        throw FFIResult.Err(RustString("Notification permissions not granted"))
      }

      // Register and wait for token
      do {
        let token = try await withCheckedThrowingContinuation { continuation in
          self.registerForPushNotificationsWithCompletion { result in
            continuation.resume(with: result)
          }
        }
        return "{\"deviceToken\":\"\(token)\"}"
      } catch {
        throw FFIResult.Err(RustString(error.localizedDescription))
      }
    #else
      throw FFIResult.Err(RustString("Push notifications are disabled in this build"))
    #endif
  }

  public func unregisterForPushNotifications() throws(FFIResult) {
    #if ENABLE_PUSH_NOTIFICATIONS
      DispatchQueue.main.async {
        NSApplication.shared.unregisterForRemoteNotifications()
        self.handlePushTokenError(NSError(
          domain: "NotificationPlugin", code: -2,
          userInfo: [NSLocalizedDescriptionKey: "Push registration cancelled"]
        ))
      }
    #else
      throw FFIResult.Err(RustString("Push notifications are disabled in this build"))
    #endif
  }

  public func checkPermissions() async throws(FFIResult) -> String {
    let settings = await notificationHandler.checkPermissions()
    let permission: String

    switch settings.authorizationStatus {
    case .authorized, .ephemeral, .provisional:
      permission = "granted"
    case .denied:
      permission = "denied"
    case .notDetermined:
      permission = "prompt"
    @unknown default:
      permission = "prompt"
    }

    return "{\"permissionState\":\"\(permission)\"}"
  }

  public func cancel(args: RustString) throws(FFIResult) {
    let args = try args.decode(CancelArgs.self)

    UNUserNotificationCenter.current().removePendingNotificationRequests(
      withIdentifiers: args.notifications.map { String($0) }
    )
  }

  public func cancelAll() throws(FFIResult) {
    UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
  }

  public func getPending() async throws(FFIResult) -> String {
    let notifications = await UNUserNotificationCenter.current().pendingNotificationRequests()

    let ret = notifications.compactMap({ [weak self] (notification) -> PendingNotification? in
      return self?.notificationHandler.toPendingNotification(notification)
    })

    return try ret.toJSONString()
  }

  public func registerActionTypes(args: RustString) throws(FFIResult) {
    let args = try args.decode(RegisterActionTypesArgs.self)
    makeCategories(args.types)
  }

  public func removeActive(args: RustString) throws(FFIResult) {
    let args = try args.decode(RemoveActiveArgs.self)
    if args.notifications.isEmpty {
      try removeAllActive()
    } else {
      UNUserNotificationCenter.current().removeDeliveredNotifications(
        withIdentifiers: args.notifications.map { String($0.id) })
    }
  }

  public func removeAllActive() throws(FFIResult) {
    let center = UNUserNotificationCenter.current()
    center.removeAllDeliveredNotifications()
    center.setBadgeCount(0) { error in
      if let error { NSLog("tauri-plugin-notifications: failed to clear the badge: \(error)") }
    }
  }

  public func getActive() async throws(FFIResult) -> String {
    let notifications = await UNUserNotificationCenter.current().deliveredNotifications()

    let ret = notifications.compactMap({ (notification) -> ActiveNotification? in
      return self.notificationHandler.toActiveNotification(notification.request)
    })

    return try ret.toJSONString()
  }

  public func setClickListenerActive(args: RustString) throws(FFIResult) {
    let args = try args.decode(SetClickListenerActiveArgs.self)
    notificationHandler.setClickListenerActive(args.active)
  }

  public func setActionListenerActive(args: RustString) throws(FFIResult) {
    let args = try args.decode(SetActionListenerActiveArgs.self)
    notificationHandler.setActionListenerActive(args.active)
  }

  #if ENABLE_PUSH_NOTIFICATIONS
    private func registerForPushNotificationsWithCompletion(_ completion: @escaping (Result<String, Error>) -> Void)
    {
      // The caller's thread may have no running run loop, so the Timer would never fire.
      DispatchQueue.main.async { [weak self] in
        guard let self = self else {
          completion(.failure(NSError(
            domain: "NotificationPlugin", code: -2,
            userInfo: [NSLocalizedDescriptionKey: "Notification plugin unavailable"]
          )))
          return
        }
        AppDelegateSwizzler.swizzlePushCallbacks()
        self.pushTokenCompletions.append(completion)
        guard self.pushTokenCompletions.count == 1 else { return }

        self.pushTokenTimer?.invalidate()
        self.pushTokenTimer = Timer.scheduledTimer(
          withTimeInterval: self.pushTokenTimeout, repeats: false
        ) { [weak self] _ in
          self?.handlePushTokenTimeout()
        }

        NSApplication.shared.registerForRemoteNotifications()
      }
    }

    private func handlePushTokenTimeout() {
      handlePushTokenError(NSError(
        domain: "NotificationPlugin",
        code: -1,
        userInfo: [NSLocalizedDescriptionKey: "Timeout waiting for device token"]
      ))
    }

    private func settlePushCompletions(_ result: Result<String, Error>) {
      pushTokenTimer?.invalidate()
      pushTokenTimer = nil

      let completions = pushTokenCompletions
      pushTokenCompletions.removeAll()
      completions.forEach { $0(result) }
    }

    func handlePushTokenReceived(_ token: String) {
      settlePushCompletions(.success(token))
    }

    func handlePushTokenError(_ error: Error) {
      settlePushCompletions(.failure(error))
    }
  #endif

  public func trigger<T: Encodable>(_ event: String, data: T) throws {
    let jsonData = try JSONEncoder().encode(data)
    guard let jsonString = String(data: jsonData, encoding: .utf8) else {
      throw NSError(
        domain: "NotificationPlugin", code: -1,
        userInfo: [NSLocalizedDescriptionKey: "Failed to encode data to JSON string"])
    }
    try bridgeTrigger(RustString(event), RustString(jsonString))
  }

  func emitJSON(_ event: String, object: Any) {
    guard JSONSerialization.isValidJSONObject(object),
      let data = try? JSONSerialization.data(withJSONObject: object),
      let json = String(data: data, encoding: .utf8)
    else {
      NSLog("tauri-plugin-notifications: '\(event)' payload is not valid JSON")
      return
    }
    do {
      try bridgeTrigger(RustString(event), RustString(json))
    } catch {
      NSLog("tauri-plugin-notifications: failed to deliver '\(event)': \(error)")
    }
  }

  func emit<T: Encodable>(_ event: String, data: T) {
    do {
      try trigger(event, data: data)
    } catch {
      NSLog("tauri-plugin-notifications: failed to deliver '\(event)': \(error)")
    }
  }
}

func jsonCompatible(_ value: Any) -> Any {
  switch value {
  case let string as String: return string
  case let number as NSNumber: return number
  case is NSNull: return NSNull()
  case let array as [Any]: return array.map(jsonCompatible)
  case let dictionary as [AnyHashable: Any]:
    return Dictionary(uniqueKeysWithValues: dictionary.map { (String(describing: $0.key), jsonCompatible($0.value)) })
  default: return String(describing: value)
  }
}

// Initialize the plugin
func initPlugin() -> NotificationPlugin {
  return NotificationPlugin()
}
