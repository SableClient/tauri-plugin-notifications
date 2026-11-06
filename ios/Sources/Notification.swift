import Tauri
import UserNotifications

enum NotificationError: LocalizedError {
  case triggerRepeatIntervalTooShort
  case attachmentFileNotFound(path: String)
  case attachmentUnableToCreate(String)
  case pastScheduledTime
  case invalidDate(String)

  var errorDescription: String? {
    switch self {
    case .triggerRepeatIntervalTooShort:
      return "Schedule interval too short, must be a least 1 minute"
    case .attachmentFileNotFound(let path):
      return "Unable to find file \(path) for attachment"
    case .attachmentUnableToCreate(let error):
      return "Failed to create attachment: \(error)"
    case .pastScheduledTime:
      return "Scheduled time must be *after* current time"
    case .invalidDate(let date):
      return "Could not parse date \(date)"
    }
  }
}

func notificationRequestIdentifier(_ notification: Notification) -> String {
  guard notification.schedule == nil,
        let user = notification.extra?["user_id"], !user.isEmpty,
        let room = notification.extra?["room_id"], !room.isEmpty,
        let event = notification.extra?["event_id"], !event.isEmpty,
        let data = try? JSONSerialization.data(withJSONObject: [user, room, event]) else {
    return String(notification.id)
  }
  return "message:" + data.base64EncodedString()
}

let localOriginKey = "__tauriNotificationLocal"

struct LocalOrigin: Codable {
  var sound: String?
  var silent: Bool?
  var schedule: NotificationSchedule?
  var attachments: [NotificationAttachment]?
}

func makeNotificationContent(_ notification: Notification) throws -> UNNotificationContent {
  let content = UNMutableNotificationContent()
  if let title = notification.title {
    content.title = NSString.localizedUserNotificationString(forKey: title, arguments: nil)
  }
  if let body = notification.body {
    content.body = NSString.localizedUserNotificationString(
      forKey: body,
      arguments: nil)
  }

  var userInfo: [AnyHashable: Any] = [:]

  // Spread `extra` directly into userInfo at the top level — matches the
  // APNs convention so the click handler can read keys from one shape
  // (whether the notification came from local schedule or remote push).
  if let extra = notification.extra {
    for (key, value) in extra {
      userInfo[key] = value
    }
  }

  let origin = LocalOrigin(
    sound: notification.sound, silent: notification.silent, schedule: notification.schedule,
    attachments: notification.attachments)
  if let encoded = try? JSONEncoder().encode(origin) {
    userInfo[localOriginKey] = String(decoding: encoded, as: UTF8.self)
  }

  content.userInfo = userInfo

  if let actionTypeId = notification.actionTypeId {
    content.categoryIdentifier = actionTypeId
  }

  if let user = notification.extra?["user_id"], !user.isEmpty,
     let room = notification.extra?["room_id"], !room.isEmpty {
    content.threadIdentifier = user + "\u{0}" + room
  } else if let threadIdentifier = notification.group {
    content.threadIdentifier = threadIdentifier
  }

  if let summaryArgument = notification.summary {
    content.summaryArgument = summaryArgument
  }

  if let sound = notification.sound {
    content.sound = UNNotificationSound(named: UNNotificationSoundName(sound))
  }

  if let attachments = notification.attachments {
    content.attachments = try makeAttachments(attachments)
  }

  return content
}

func makeAttachments(_ attachments: [NotificationAttachment]) throws -> [UNNotificationAttachment] {
  var createdAttachments = [UNNotificationAttachment]()

  for attachment in attachments {

    guard let urlObject = makeAttachmentUrl(attachment.url) else {
      throw NotificationError.attachmentFileNotFound(path: attachment.url)
    }

    let options = attachment.options != nil ? makeAttachmentOptions(attachment.options!) : nil

    // The system moves the attached file into its store when the request is added, so attach a copy.
    let scratch = FileManager.default.temporaryDirectory
      .appendingPathComponent(UUID().uuidString, isDirectory: true)

    do {
      var source = urlObject
      if urlObject.isFileURL {
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        source = scratch.appendingPathComponent(urlObject.lastPathComponent)
        try FileManager.default.copyItem(at: urlObject, to: source)
      }
      let newAttachment = try UNNotificationAttachment(
        identifier: attachment.id, url: source, options: options)
      createdAttachments.append(newAttachment)
    } catch {
      throw NotificationError.attachmentUnableToCreate(error.localizedDescription)
    }
  }

  return createdAttachments
}

func makeAttachmentUrl(_ path: String) -> URL? {
  // UNNotificationAttachment needs a file URL, and URL(string:) leaves an
  // absolute filesystem path schemeless. Relative/empty input stays nil.
  if path.hasPrefix("/") {
    return URL(fileURLWithPath: path)
  }
  return URL(string: path)
}

func makeAttachmentOptions(_ options: NotificationAttachmentOptions) -> [AnyHashable: Any] {
  var opts: [AnyHashable: Any] = [:]

  if let value = options.iosUNNotificationAttachmentOptionsTypeHintKey {
    opts[UNNotificationAttachmentOptionsTypeHintKey] = value
  }
  if let value = options.iosUNNotificationAttachmentOptionsThumbnailHiddenKey,
    let hidden = Bool(value.lowercased())
  {
    opts[UNNotificationAttachmentOptionsThumbnailHiddenKey] = NSNumber(value: hidden)
  }
  // Accepts "x,y,width,height" or "{{x, y}, {width, height}}".
  if let value = options.iosUNNotificationAttachmentOptionsThumbnailClippingRectKey {
    let numbers = value.components(separatedBy: CharacterSet(charactersIn: "{}, "))
      .filter { !$0.isEmpty }.compactMap(Double.init)
    if numbers.count == 4 {
      let rect = CGRect(x: numbers[0], y: numbers[1], width: numbers[2], height: numbers[3])
      opts[UNNotificationAttachmentOptionsThumbnailClippingRectKey] = rect.dictionaryRepresentation
    }
  }
  if let value = options.iosUNNotificationAttachmentOptionsThumbnailTimeKey,
    let seconds = Double(value)
  {
    opts[UNNotificationAttachmentOptionsThumbnailTimeKey] = NSNumber(value: seconds)
  }
  return opts
}

func parseScheduleDate(_ value: String) -> Date? {
  var text = value
  var hasFraction = false
  if let range = text.range(of: #"\.\d+"#, options: .regularExpression) {
    hasFraction = true
    let digits = text[text.index(after: range.lowerBound)..<range.upperBound].prefix(3)
    text.replaceSubrange(
      range, with: "." + digits + String(repeating: "0", count: 3 - digits.count))
  }
  let formatter = ISO8601DateFormatter()
  formatter.formatOptions =
    hasFraction ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
  return formatter.date(from: text)
}

func handleScheduledNotification(_ schedule: NotificationSchedule) throws
  -> UNNotificationTrigger?
{
  switch schedule {
  case .at(let date, let repeating):
    guard let at = parseScheduleDate(date) else {
      throw NotificationError.invalidDate(date)
    }
    let now = Date()
    if repeating {
      // Repeats daily at the same wall-clock time, so a past start date is fine.
      return calendarTrigger([.hour, .minute, .second], from: at)
    }
    guard at > now else {
      Logger.debug("Scheduled time is in the past: \(at) < \(now)")
      throw NotificationError.pastScheduledTime
    }
    return UNTimeIntervalNotificationTrigger(timeInterval: at.timeIntervalSince(now), repeats: false)
  case .interval(let interval):
    let dateComponents = getDateComponents(interval)
    return UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: true)
  case .every(let interval, let count):
    // Calendar triggers follow wall-clock time across DST and month lengths but
    // cannot express a multiple of the unit, so those keep a fixed interval.
    if count == 1, let units = calendarUnits(interval) {
      return calendarTrigger(units, from: Date())
    }
    if let repeatDateInterval = getRepeatDateInterval(interval, count) {
      // Notifications that repeat have to be at least a minute between each other
      if repeatDateInterval.duration < 60 {
        throw NotificationError.triggerRepeatIntervalTooShort
      }

      return UNTimeIntervalNotificationTrigger(
        timeInterval: repeatDateInterval.duration, repeats: true)
    }
  }

  return nil
}

private func calendarUnits(_ every: ScheduleEveryKind) -> Set<Calendar.Component>? {
  let time: Set<Calendar.Component> = [.hour, .minute, .second]
  switch every {
  case .day: return time
  case .week: return time.union([.weekday])
  case .month: return time.union([.day])
  case .year: return time.union([.month, .day])
  case .twoWeeks, .hour, .minute, .second: return nil
  }
}

private func calendarTrigger(_ units: Set<Calendar.Component>, from date: Date)
  -> UNCalendarNotificationTrigger
{
  UNCalendarNotificationTrigger(
    dateMatching: Calendar.current.dateComponents(units, from: date), repeats: true)
}

/// Given our schedule format, return a DateComponents object
/// that only contains the components passed in.

func getDateComponents(_ at: ScheduleInterval) -> DateComponents {
  // var dateInfo = Calendar.current.dateComponents(in: TimeZone.current, from: Date())
  // dateInfo.calendar = Calendar.current
  var dateInfo = DateComponents()

  if let year = at.year {
    dateInfo.year = year
  }
  if let month = at.month {
    dateInfo.month = month
  }
  if let day = at.day {
    dateInfo.day = day
  }
  if let hour = at.hour {
    dateInfo.hour = hour
  }
  if let minute = at.minute {
    dateInfo.minute = minute
  }
  if let second = at.second {
    dateInfo.second = second
  }
  if let weekday = at.weekday {
    dateInfo.weekday = weekday
  }
  return dateInfo
}

/// Compute the difference between the string representation of a date
/// interval and today. For example, if every is "month", then we
/// return the interval between today and a month from today.

func getRepeatDateInterval(_ every: ScheduleEveryKind, _ count: Int) -> DateInterval? {
  let cal = Calendar.current
  let now = Date()
  switch every {
  case .year:
    let newDate = cal.date(byAdding: .year, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .month:
    let newDate = cal.date(byAdding: .month, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .twoWeeks:
    let newDate = cal.date(byAdding: .weekOfYear, value: 2 * count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .week:
    let newDate = cal.date(byAdding: .weekOfYear, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .day:
    let newDate = cal.date(byAdding: .day, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .hour:
    let newDate = cal.date(byAdding: .hour, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .minute:
    let newDate = cal.date(byAdding: .minute, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  case .second:
    let newDate = cal.date(byAdding: .second, value: count, to: now)!
    return DateInterval(start: now, end: newDate)
  }
}
