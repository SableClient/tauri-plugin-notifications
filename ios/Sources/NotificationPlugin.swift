import SwiftRs
import Tauri
import UIKit
import UserNotifications
import WebKit

enum ShowNotificationError: LocalizedError {
  case make(Error)
  case create(Error)

  var errorDescription: String? {
    switch self {
    case .make(let error):
      return "Unable to make notification: \(error)"
    case .create(let error):
      return "Unable to create notification: \(error)"
    }
  }
}

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

enum JSONValue: Codable {
  case string(String)
  case bool(Bool)
  case int(Int)
  case double(Double)
  case null
  case array([JSONValue])
  case object([String: JSONValue])

  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    if container.decodeNil() {
      self = .null
    } else if let value = try? container.decode(Bool.self) {
      self = .bool(value)
    } else if let value = try? container.decode(Int.self) {
      self = .int(value)
    } else if let value = try? container.decode(Double.self) {
      self = .double(value)
    } else if let value = try? container.decode(String.self) {
      self = .string(value)
    } else if let value = try? container.decode([JSONValue].self) {
      self = .array(value)
    } else {
      self = .object(try container.decode([String: JSONValue].self))
    }
  }

  func encode(to encoder: Encoder) throws {
    var container = encoder.singleValueContainer()
    switch self {
    case .string(let value): try container.encode(value)
    case .bool(let value): try container.encode(value)
    case .int(let value): try container.encode(value)
    case .double(let value): try container.encode(value)
    case .null: try container.encodeNil()
    case .array(let value): try container.encode(value)
    case .object(let value): try container.encode(value)
    }
  }

  var extraString: String? {
    switch self {
    case .string(let value): return value
    case .bool(let value): return value ? "true" : "false"
    case .int(let value): return String(value)
    case .double(let value): return String(value)
    case .null: return nil
    case .array, .object:
      let encoder = JSONEncoder()
      encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
      return (try? encoder.encode(self)).flatMap { String(data: $0, encoding: .utf8) }
    }
  }
}

struct Notification: Decodable {
  let id: Int
  var title: String?
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

extension Notification {
  enum CodingKeys: String, CodingKey {
    case id, title, body, extra, schedule, attachments, sound, group, actionTypeId, summary, silent
  }

  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    id = try container.decode(Int.self, forKey: .id)
    title = try container.decodeIfPresent(String.self, forKey: .title)
    body = try container.decodeIfPresent(String.self, forKey: .body)
    extra = try container.decodeIfPresent([String: JSONValue].self, forKey: .extra)?
      .compactMapValues(\.extraString)
    schedule = try container.decodeIfPresent(NotificationSchedule.self, forKey: .schedule)
    attachments = try container.decodeIfPresent([NotificationAttachment].self, forKey: .attachments)
    sound = try container.decodeIfPresent(String.self, forKey: .sound)
    group = try container.decodeIfPresent(String.self, forKey: .group)
    actionTypeId = try container.decodeIfPresent(String.self, forKey: .actionTypeId)
    summary = try container.decodeIfPresent(String.self, forKey: .summary)
    silent = try container.decodeIfPresent(Bool.self, forKey: .silent)
  }
}

struct RemoveActiveNotification: Decodable {
  let id: Int
}

struct RemoveActiveArgs: Decodable {
  let notifications: [RemoveActiveNotification]
}

func makeNotificationRequest(_ notification: Notification) throws -> UNNotificationRequest {
  var content: UNNotificationContent
  do {
    content = try makeNotificationContent(notification)
  } catch {
    throw ShowNotificationError.make(error)
  }

  var trigger: UNNotificationTrigger?

  do {
    if let schedule = notification.schedule {
      try trigger = handleScheduledNotification(schedule)
    }
  } catch {
    throw ShowNotificationError.create(error)
  }

  return UNNotificationRequest(
    identifier: notificationRequestIdentifier(notification), content: content, trigger: trigger
  )
}

func showNotification(request: UNNotificationRequest, completion: @escaping (Error?) -> Void) {
  let content = request.content
  let center = UNUserNotificationCenter.current()
  func add() {
    center.add(request) { (error: Error?) in completion(error) }
  }
  if request.trigger == nil && content.userInfo["event_id"] != nil {
    center.getDeliveredNotifications { delivered in
      let matching = delivered.filter {
        NotificationHandler.matchesMessage($0.request.content.userInfo, content.userInfo)
      }
      guard !matching.isEmpty else { add(); return }
      guard !matching.contains(where: { $0.request.content.body == content.body }) else {
        completion(nil)
        return
      }
      // Enrichment changes content, not the event: replace it without a second sound.
      guard let updated = content.mutableCopy() as? UNMutableNotificationContent else {
        completion(nil)
        return
      }
      updated.sound = nil
      center.removeDeliveredNotifications(withIdentifiers: matching.map { $0.request.identifier })
      center.add(UNNotificationRequest(identifier: request.identifier, content: updated, trigger: nil)) { error in
        completion(error)
      }
    }
  } else { add() }
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

struct BatchArgs: Decodable {
  let notifications: [Notification]
}

struct SetClickListenerActiveArgs: Decodable {
  let active: Bool
}

struct SetActionListenerActiveArgs: Decodable {
  let active: Bool
}

struct SetPushMessageListenerActiveArgs: Decodable {
  let active: Bool
}

struct PluginConfig: Decodable {
  let actionTypes: [ActionType]?
}

class NotificationPlugin: Plugin {
  let notificationHandler = NotificationHandler()
  let notificationManager = NotificationManager()

  #if ENABLE_PUSH_NOTIFICATIONS
    // Completion handler for push token registration
    private var pushTokenCompletions = [(Result<String, Error>) -> Void]()
    var isRegisteringPush: Bool { !pushTokenCompletions.isEmpty }
    private let pushTokenTimeout: TimeInterval = 10.0
    private var pushTokenTimer: Timer?
  #endif

  override init() {
    super.init()
    notificationManager.notificationHandler = notificationHandler
    notificationHandler.plugin = self
  }

  public override func load(webview: WKWebView) {
    super.load(webview: webview)

    if let config = try? parseConfig(PluginConfig.self),
       let actionTypes = config.actionTypes {
      makeCategories(actionTypes)
    }

    #if ENABLE_PUSH_NOTIFICATIONS
      // Store reference to this plugin for event triggering
      AppDelegateSwizzler.plugin = self

      // swizzle UIApplicationDelegate push methods
      AppDelegateSwizzler.swizzlePushCallbacks()
    #endif
  }

  @objc public func show(_ invoke: Invoke) throws {
    let notification = try invoke.parseArgs(Notification.self)
    let request = try makeNotificationRequest(notification)
    showNotification(request: request) { error in
      if let error {
        invoke.reject(error.localizedDescription)
      } else {
        invoke.resolve(notification.id)
      }
    }
  }

  @objc public func batch(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(BatchArgs.self)
    let requests = try args.notifications.map(makeNotificationRequest)
    let group = DispatchGroup()
    let failure = NSLock()
    var firstError: Error?

    for request in requests {
      group.enter()
      showNotification(request: request) { error in
        if let error {
          failure.lock()
          firstError = firstError ?? error
          failure.unlock()
        }
        group.leave()
      }
    }

    group.notify(queue: .main) {
      if let firstError {
        invoke.reject(firstError.localizedDescription)
      } else {
        invoke.resolve(args.notifications.map { $0.id })
      }
    }
  }

  @objc public override func requestPermissions(_ invoke: Invoke) {
    notificationHandler.requestPermissions { granted, error in
      guard error == nil else {
        invoke.reject(error!.localizedDescription)
        return
      }

      let permissionState = granted ? "granted" : "denied"
      invoke.resolve(["permissionState": permissionState])
    }
  }

  @objc public func registerForPushNotifications(_ invoke: Invoke) {
    #if ENABLE_PUSH_NOTIFICATIONS
      notificationHandler.ensureDeliveryAllowed { [weak self] granted, error in
        guard error == nil else {
          invoke.reject(error!.localizedDescription)
          return
        }

        guard granted else {
          invoke.reject("Notification permission denied")
          return
        }
        guard let self = self else {
          invoke.reject("Notification plugin unavailable")
          return
        }
        self.registerForPushNotifications { result in
          switch result {
          case .success(let token):
            invoke.resolve(["deviceToken": token])
          case .failure(let error):
            invoke.reject(error.localizedDescription)
          }
        }
      }
    #else
      invoke.reject("Push notifications are disabled in this build")
    #endif
  }

  @objc public func unregisterForPushNotifications(_ invoke: Invoke) {
    #if ENABLE_PUSH_NOTIFICATIONS
      DispatchQueue.main.async {
        UIApplication.shared.unregisterForRemoteNotifications()
        self.handlePushTokenError(NSError(
          domain: "NotificationPlugin", code: -2,
          userInfo: [NSLocalizedDescriptionKey: "Push registration cancelled"]
        ))
        invoke.resolve()
      }
    #else
      invoke.reject("Push notifications are disabled in this build")
    #endif
  }

  #if ENABLE_PUSH_NOTIFICATIONS
    private func registerForPushNotifications(completion: @escaping (Result<String, Error>) -> Void)
    {
      // Main queue: the caller runs on an arbitrary thread whose run loop never
      // runs (so the Timer would never fire), and this serializes the token state.
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

        UIApplication.shared.registerForRemoteNotifications()
      }
    }

    private func handlePushTokenTimeout() {
      pushTokenTimer?.invalidate()
      pushTokenTimer = nil

      let completions = pushTokenCompletions
      pushTokenCompletions.removeAll()
      if !completions.isEmpty {
        let error = NSError(
          domain: "NotificationPlugin",
          code: -1,
          userInfo: [NSLocalizedDescriptionKey: "Timeout waiting for device token"]
        )
        completions.forEach { $0(.failure(error)) }
      }
    }

    // Called by AppDelegateSwizzler when token is received
    func handlePushTokenReceived(_ token: String) {
      pushTokenTimer?.invalidate()
      pushTokenTimer = nil

      let completions = pushTokenCompletions
      pushTokenCompletions.removeAll()
      completions.forEach { $0(.success(token)) }
    }

    // Called by AppDelegateSwizzler when registration fails
    func handlePushTokenError(_ error: Error) {
      pushTokenTimer?.invalidate()
      pushTokenTimer = nil

      let completions = pushTokenCompletions
      pushTokenCompletions.removeAll()
      completions.forEach { $0(.failure(error)) }
    }
  #endif

  @objc public override func checkPermissions(_ invoke: Invoke) {
    notificationHandler.checkPermissions { status in
      let permission: String

      switch status {
      case .authorized, .ephemeral, .provisional:
        permission = "granted"
      case .denied:
        permission = "denied"
      case .notDetermined:
        permission = "prompt"
      @unknown default:
        permission = "prompt"
      }

      invoke.resolve(["permissionState": permission])
    }
  }

  @objc func cancel(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(CancelArgs.self)

    let identifiers = args.notifications.map { String($0) }
    UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: identifiers)
    invoke.resolve()
  }

  @objc func cancelAll(_ invoke: Invoke) {
    let center = UNUserNotificationCenter.current()
    center.removeAllPendingNotificationRequests()
    invoke.resolve()
  }

  @objc func getPending(_ invoke: Invoke) {
    UNUserNotificationCenter.current().getPendingNotificationRequests(completionHandler: {
      (notifications) in
      let ret = notifications.compactMap({ [weak self] (notification) -> PendingNotification? in
        return self?.notificationHandler.toPendingNotification(notification)
      })

      invoke.resolve(ret)
    })
  }

  @objc func registerActionTypes(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(RegisterActionTypesArgs.self)
    makeCategories(args.types)
    invoke.resolve()
  }

  @objc func removeActive(_ invoke: Invoke) throws {
    let center = UNUserNotificationCenter.current()
    // Rust serializes the argument-less remove-all call as null
    guard invoke.getRawArgs() != "null" else {
      center.removeAllDeliveredNotifications()
      if #available(iOS 16.0, *) {
        center.setBadgeCount(0) { error in
          if let error { Logger.error("Failed to clear the badge: \(error)") }
        }
      } else {
        DispatchQueue.main.async(execute: {
          UIApplication.shared.applicationIconBadgeNumber = 0
        })
      }
      invoke.resolve()
      return
    }
    let ids = Set(try invoke.parseArgs(RemoveActiveArgs.self).notifications.map { $0.id })
    center.getDeliveredNotifications { [weak self] delivered in
      guard let self else { return invoke.resolve() }
      let identifiers = delivered.filter {
        self.notificationHandler.matches($0.request, ids: ids)
      }.map { $0.request.identifier }
      center.removeDeliveredNotifications(withIdentifiers: identifiers)
      invoke.resolve()
    }
  }

  @objc func getActive(_ invoke: Invoke) {
    UNUserNotificationCenter.current().getDeliveredNotifications(completionHandler: {
      (notifications) in
      let ret = notifications.map({ (notification) -> ActiveNotification in
        return self.notificationHandler.toActiveNotification(notification.request)
          ?? self.notificationHandler.toRemoteActionNotification(notification.request)
      })
      invoke.resolve(ret)
    })
  }

  @objc func setClickListenerActive(_ invoke: Invoke) {
    do {
      let args = try invoke.parseArgs(SetClickListenerActiveArgs.self)
      notificationHandler.setClickListenerActive(args.active)
      invoke.resolve()
    } catch {
      invoke.reject(error.localizedDescription)
    }
  }

  @objc func setActionListenerActive(_ invoke: Invoke) {
    do {
      let args = try invoke.parseArgs(SetActionListenerActiveArgs.self)
      notificationHandler.setActionListenerActive(args.active)
      invoke.resolve()
    } catch {
      invoke.reject(error.localizedDescription)
    }
  }

  // No-op on iOS: remote notifications are delivered through APNs /
  // UNUserNotificationCenter regardless of JS listener readiness, so
  // push-message delivery is not gated on listener state. Exists for
  // command parity with Android, where the gating prevents drops.
  @objc func setPushMessageListenerActive(_ invoke: Invoke) {
    do {
      _ = try invoke.parseArgs(SetPushMessageListenerActiveArgs.self)
      invoke.resolve()
    } catch {
      invoke.reject(error.localizedDescription)
    }
  }
}

@_cdecl("init_plugin_notification")
func initPlugin() -> Plugin {
  return NotificationPlugin()
}
