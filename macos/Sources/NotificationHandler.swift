import UserNotifications

public class NotificationHandler: NSObject, NotificationHandlerProtocol {

  weak var plugin: NotificationPlugin?

  private var hasClickedListener = false
  private var pendingNotificationClick: NotificationClickedData? = nil
  private var hasActionListener = false
  private var pendingNotificationActions = [ReceivedNotification]()
  private let maxPendingActions = 32
  private let stateLock = NSRecursiveLock()

  func setClickListenerActive(_ active: Bool) {
    stateLock.lock()
    hasClickedListener = active
    let pending = active ? pendingNotificationClick : nil
    if pending != nil { pendingNotificationClick = nil }
    stateLock.unlock()
    if let pending { self.plugin?.emit("notificationClicked", data: pending) }
  }

  func setActionListenerActive(_ active: Bool) {
    stateLock.lock()
    hasActionListener = active
    let pendingActions = active ? pendingNotificationActions : []
    if active { pendingNotificationActions.removeAll() }
    stateLock.unlock()
    for action in pendingActions {
      self.plugin?.emit("actionPerformed", data: action)
    }
  }

  public func requestPermissions() async throws -> Bool {
    let center = UNUserNotificationCenter.current()
    return try await center.requestAuthorization(options: [.badge, .alert, .sound])
  }

  static func allowsDelivery(_ status: UNAuthorizationStatus) -> Bool {
    switch status {
    case .authorized, .provisional, .ephemeral: return true
    default: return false
    }
  }

  /// A provisional grant makes the request report `granted == false`.
  func ensureDeliveryAllowed() async throws -> Bool {
    if Self.allowsDelivery(await checkPermissions().authorizationStatus) { return true }
    return try await requestPermissions()
  }

  public func checkPermissions() async -> UNNotificationSettings {
    let center = UNUserNotificationCenter.current()
    return await center.notificationSettings()
  }

  public func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
    // Trigger notification event for both local and push notifications
    if var notificationData = toActiveNotification(notification.request) {
      notificationData.source = "local"
      self.plugin?.emit("notification", data: notificationData)
    } else {
      var notificationData = toReceivedNotification(notification.request)
      notificationData.source = "push"
      self.plugin?.emit("notification", data: notificationData)
    }

    // For push notifications in foreground, don't show system notification
    // (only trigger event so developer can handle it)
    let isPushNotification = notification.request.trigger?.isKind(of: UNPushNotificationTrigger.self) == true
    if isPushNotification {
      return UNNotificationPresentationOptions.init(rawValue: 0)
    }

    // For local notifications, check if silent
    if Self.localOrigin(notification.request.content.userInfo)?.silent ?? false {
      return UNNotificationPresentationOptions.init(rawValue: 0)
    }

    return [.banner, .list, .badge, .sound]
  }

  /// Convert notification request to ReceivedNotification (for push notifications not in map)
  private func toReceivedNotification(_ request: UNNotificationRequest) -> ReceivedNotificationData {
    let content = request.content

    return ReceivedNotificationData(
      id: Int(request.identifier) ?? -1,
      title: content.title,
      body: content.body,
      extra: Self.notificationExtra(content.userInfo)
    )
  }

  public func didReceive(response: UNNotificationResponse) {
    stateLock.lock()
    let originalNotificationRequest = response.notification.request
    let actionId = response.actionIdentifier

    var actionIdValue: String
    // We turn the two default actions (open/dismiss) into generic strings
    if actionId == UNNotificationDefaultActionIdentifier {
      actionIdValue = "tap"
    } else if actionId == UNNotificationDismissActionIdentifier {
      actionIdValue = "dismiss"
    } else {
      actionIdValue = actionId
    }

    var inputValue: String? = nil
    // If the type of action was for an input type, get the value
    if let inputType = response as? UNTextInputNotificationResponse {
      inputValue = inputType.userText
    }

    let actionNotification = toActiveNotification(originalNotificationRequest)
      ?? toRemoteActionNotification(originalNotificationRequest)
    let action = ReceivedNotification(
      actionId: actionIdValue,
      inputValue: inputValue,
      notification: actionNotification
    )
    let shouldTriggerAction = hasActionListener
    if !shouldTriggerAction {
      if pendingNotificationActions.count >= maxPendingActions { pendingNotificationActions.removeFirst() }
      pendingNotificationActions.append(action)
    }

    if actionId != UNNotificationDefaultActionIdentifier {
      stateLock.unlock()
      if shouldTriggerAction { self.plugin?.emit("actionPerformed", data: action) }
      return
    }

    let clickedData = NotificationClickedData(
      id: Int(originalNotificationRequest.identifier) ?? -1,
      data: Self.notificationExtra(originalNotificationRequest.content.userInfo)
    )
    let shouldTriggerClick = hasClickedListener
    if !shouldTriggerClick {
      // No listener (cold-start), store for later
      pendingNotificationClick = clickedData
    }
    stateLock.unlock()
    if shouldTriggerAction { self.plugin?.emit("actionPerformed", data: action) }
    if shouldTriggerClick { self.plugin?.emit("notificationClicked", data: clickedData) }
  }

  static func localOrigin(_ userInfo: [AnyHashable: Any]) -> LocalOrigin? {
    guard let encoded = userInfo[localOriginKey] as? String else { return nil }
    return try? JSONDecoder().decode(LocalOrigin.self, from: Data(encoded.utf8))
  }

  func toActiveNotification(_ request: UNNotificationRequest) -> ActiveNotification? {
    guard let origin = Self.localOrigin(request.content.userInfo) else {
      return nil
    }
    return ActiveNotification(
      id: Int(request.identifier) ?? -1,
      title: request.content.title,
      body: request.content.body,
      sound: origin.sound ?? "",
      actionTypeId: request.content.categoryIdentifier,
      attachments: origin.attachments,
      extra: Self.notificationExtra(request.content.userInfo)
    )
  }

  func toRemoteActionNotification(_ request: UNNotificationRequest) -> ActiveNotification {
    ActiveNotification(
      id: Int(request.identifier) ?? -1,
      title: request.content.title,
      body: request.content.body,
      sound: "",
      actionTypeId: request.content.categoryIdentifier,
      attachments: nil,
      extra: Self.notificationExtra(request.content.userInfo),
      source: "push"
    )
  }

  static func notificationExtra(_ userInfo: [AnyHashable: Any]) -> [String: String]? {
    var notification = userInfo["notification"] as? [String: Any]
    if let encoded = userInfo["notification"] as? String,
       let data = encoded.data(using: .utf8) {
      notification = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
    var recipients = Set<String>()
    func addRecipient(_ value: Any?) {
      guard let value = value as? String else { return }
      let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
      if !trimmed.isEmpty { recipients.insert(trimmed) }
    }
    addRecipient(userInfo["user_id"])
    addRecipient(notification?["user_id"])
    let devices = notification?["devices"] ?? userInfo["devices"]
    for device in devices as? [[String: Any]] ?? [] {
      guard let data = device["data"] as? [String: Any] else { continue }
      addRecipient(data["user_id"])
      addRecipient((data["default_payload"] as? [String: Any])?["user_id"])
    }
    guard recipients.count <= 1 else { return nil }
    var extra = [String: String]()
    func merge(_ values: [AnyHashable: Any]) {
      for (key, value) in values {
        guard let key = key as? String, key != "aps", key != "notification", key != localOriginKey else { continue }
        if let value = value as? String {
          extra[key] = value
        } else if let value = value as? NSNumber {
          extra[key] = value.stringValue
        }
      }
    }
    merge(userInfo)
    if let notification { merge(notification) }
    if let recipient = recipients.first { extra["user_id"] = recipient }
    return extra.isEmpty ? nil : extra
  }

  func toPendingNotification(_ request: UNNotificationRequest) -> PendingNotification? {
    guard let schedule = Self.localOrigin(request.content.userInfo)?.schedule else {
      return nil
    }
    return PendingNotification(
      id: Int(request.identifier) ?? -1,
      title: request.content.title,
      body: request.content.body,
      schedule: schedule
    )
  }
}

struct PendingNotification: Encodable {
  let id: Int
  let title: String
  let body: String
  let schedule: NotificationSchedule
}

struct ActiveNotification: Encodable {
  let id: Int
  let title: String
  let body: String
  let sound: String
  let actionTypeId: String
  let attachments: [NotificationAttachment]?
  var extra: [String: String]? = nil
  var source: String = "local"
}

struct ReceivedNotification: Encodable {
  let actionId: String
  let inputValue: String?
  let notification: ActiveNotification
}

struct NotificationClickedData: Encodable {
  let id: Int
  let data: [String: String]?
}

struct ReceivedNotificationData: Encodable {
  let id: Int
  let title: String
  let body: String
  let extra: [String: String]?
  var source: String = "push"
}
