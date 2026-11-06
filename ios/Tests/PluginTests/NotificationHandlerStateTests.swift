import XCTest
import UserNotifications
@testable import tauri_plugin_notifications

final class NotificationHandlerStateTests: XCTestCase {
  private func decode(_ json: String) throws -> tauri_plugin_notifications.Notification {
    try JSONDecoder().decode(tauri_plugin_notifications.Notification.self, from: Data(json.utf8))
  }

  private func request(
    _ json: String, identifier: String = "1", trigger: UNNotificationTrigger? = nil
  ) throws -> UNNotificationRequest {
    UNNotificationRequest(
      identifier: identifier, content: try makeNotificationContent(try decode(json)),
      trigger: trigger)
  }

  func testLocalNotificationIsRecognisedFromItsRequest() throws {
    let handler = NotificationHandler()
    let local = try request(#"{"id":1,"title":"t","sound":"ping.wav"}"#)
    XCTAssertEqual(handler.toActiveNotification(local)?.sound, "ping.wav")
    XCTAssertEqual(handler.toActiveNotification(local)?.source, "local")

    let push = UNNotificationRequest(
      identifier: "1", content: UNMutableNotificationContent(), trigger: nil)
    XCTAssertNil(handler.toActiveNotification(push))
  }

  func testSilentFlagSurvivesInTheRequest() throws {
    let silent = try request(#"{"id":1,"title":"t","silent":true}"#)
    XCTAssertEqual(NotificationHandler.localOrigin(silent.content.userInfo)?.silent, true)
    let loud = try request(#"{"id":1,"title":"t"}"#)
    XCTAssertNotEqual(NotificationHandler.localOrigin(loud.content.userInfo)?.silent, true)
  }

  func testPendingNotificationIsRebuiltFromTheScheduledRequest() throws {
    let handler = NotificationHandler()
    let scheduled = try request(
      #"{"id":7,"title":"t","body":"b","schedule":{"every":{"interval":"day","count":1}}}"#,
      identifier: "7")
    let pending = handler.toPendingNotification(scheduled)
    XCTAssertEqual(pending?.id, 7)
    XCTAssertEqual(pending?.title, "t")
    XCTAssertNil(handler.toPendingNotification(try request(#"{"id":7,"title":"t"}"#)))
  }

  func testReservedKeyIsNotExposedAsExtra() throws {
    let handler = NotificationHandler()
    let local = try request(#"{"id":1,"title":"t","extra":{"a":"b"}}"#)
    XCTAssertEqual(handler.toActiveNotification(local)?.extra, ["a": "b"])
  }

  func testNotificationWithoutTitleDecodes() throws {
    let notification = try decode(#"{"id":1,"title":null,"body":"b"}"#)
    XCTAssertNil(notification.title)
    XCTAssertEqual(try makeNotificationContent(notification).title, "")
    XCTAssertNil(try decode(#"{"id":1}"#).title)
  }

  func testExtraAcceptsNonStringValues() throws {
    let notification = try decode(
      #"{"id":1,"extra":{"s":"text","n":3,"d":1.5,"t":true,"z":null,"o":{"b":1,"a":[1,"x"]},"l":[1]}}"#)
    XCTAssertEqual(
      notification.extra,
      ["s": "text", "n": "3", "d": "1.5", "t": "true", "o": #"{"a":[1,"x"],"b":1}"#, "l": "[1]"])
  }

  func testProvisionalAndEphemeralGrantsAllowDelivery() {
    XCTAssertTrue(NotificationHandler.allowsDelivery(.authorized))
    XCTAssertTrue(NotificationHandler.allowsDelivery(.provisional))
    XCTAssertTrue(NotificationHandler.allowsDelivery(.ephemeral))
    XCTAssertFalse(NotificationHandler.allowsDelivery(.denied))
    XCTAssertFalse(NotificationHandler.allowsDelivery(.notDetermined))
  }

  func testScheduleDatesParseWithOffsetsAndAnyFractionLength() throws {
    let expected = Date(timeIntervalSince1970: 1_767_225_600)
    for value in [
      "2026-01-01T00:00:00Z",
      "2026-01-01T00:00:00.000Z",
      "2026-01-01T00:00:00.000000000Z",
      "2026-01-01T01:00:00+01:00",
      "2025-12-31T19:00:00.5-05:00",
    ] {
      let parsed = try XCTUnwrap(parseScheduleDate(value), value)
      XCTAssertEqual(parsed.timeIntervalSince1970, expected.timeIntervalSince1970, accuracy: 0.6, value)
    }
    XCTAssertNil(parseScheduleDate("2026-01-01T00:00:00"))
    XCTAssertNil(parseScheduleDate("invalid-date"))
  }

  func testClicksReceivedWithoutAListenerAreKeptInOrderAndBounded() {
    let handler = NotificationHandler()
    for id in 1...10 {
      XCTAssertFalse(handler.deliversClick(NotificationClickedData(id: id, data: nil)))
    }
    XCTAssertEqual(handler.pendingClickIds, Array(3...10))

    handler.setClickListenerActive(true)
    XCTAssertEqual(handler.pendingClickIds, [])
    XCTAssertTrue(handler.deliversClick(NotificationClickedData(id: 11, data: nil)))
  }

  func testEveryDayFollowsTheWallClockInsteadOfAFixedInterval() throws {
    let day = try XCTUnwrap(
      try handleScheduledNotification(.every(interval: .day, count: 1)) as? UNCalendarNotificationTrigger)
    XCTAssertTrue(day.repeats)
    XCTAssertNotNil(day.dateComponents.hour)
    XCTAssertNil(day.dateComponents.day)

    let month = try XCTUnwrap(
      try handleScheduledNotification(.every(interval: .month, count: 1)) as? UNCalendarNotificationTrigger)
    XCTAssertNotNil(month.dateComponents.day)
    XCTAssertNil(month.dateComponents.month)

    let year = try XCTUnwrap(
      try handleScheduledNotification(.every(interval: .year, count: 1)) as? UNCalendarNotificationTrigger)
    XCTAssertNotNil(year.dateComponents.month)

    let week = try XCTUnwrap(
      try handleScheduledNotification(.every(interval: .week, count: 1)) as? UNCalendarNotificationTrigger)
    XCTAssertNotNil(week.dateComponents.weekday)

    let everyTwoDays = try XCTUnwrap(
      try handleScheduledNotification(.every(interval: .day, count: 2)) as? UNTimeIntervalNotificationTrigger)
    XCTAssertEqual(everyTwoDays.timeInterval, 2 * 86400, accuracy: 1)
  }

  func testRepeatingAtMatchesTheWallClockTime() throws {
    let future = Date().addingTimeInterval(7200)
    let schedule = NotificationSchedule.at(
      date: ISO8601DateFormatter().string(from: future), repeating: true)
    let trigger = try XCTUnwrap(try handleScheduledNotification(schedule) as? UNCalendarNotificationTrigger)
    let expected = Calendar.current.dateComponents([.hour, .minute, .second], from: future)
    XCTAssertTrue(trigger.repeats)
    XCTAssertEqual(trigger.dateComponents.hour, expected.hour)
    XCTAssertEqual(trigger.dateComponents.minute, expected.minute)
    XCTAssertEqual(trigger.dateComponents.second, expected.second)
  }

  func testAttachmentOptionsUseTheTypesUserNotificationsExpects() throws {
    let options = NotificationAttachmentOptions(
      iosUNNotificationAttachmentOptionsTypeHintKey: nil,
      iosUNNotificationAttachmentOptionsThumbnailHiddenKey: "true",
      iosUNNotificationAttachmentOptionsThumbnailClippingRectKey: "0.1,0.2,0.5,0.6",
      iosUNNotificationAttachmentOptionsThumbnailTimeKey: "2.5")
    let result = makeAttachmentOptions(options)
    XCTAssertEqual(result[UNNotificationAttachmentOptionsThumbnailHiddenKey] as? NSNumber, true)
    XCTAssertEqual(result[UNNotificationAttachmentOptionsThumbnailTimeKey] as? NSNumber, 2.5)
    let rect = CGRect(
      dictionaryRepresentation: try XCTUnwrap(
        result[UNNotificationAttachmentOptionsThumbnailClippingRectKey] as CFTypeRef?) as! CFDictionary)
    XCTAssertEqual(rect, CGRect(x: 0.1, y: 0.2, width: 0.5, height: 0.6))

    let bad = NotificationAttachmentOptions(
      iosUNNotificationAttachmentOptionsTypeHintKey: nil,
      iosUNNotificationAttachmentOptionsThumbnailHiddenKey: "maybe",
      iosUNNotificationAttachmentOptionsThumbnailClippingRectKey: "1,2",
      iosUNNotificationAttachmentOptionsThumbnailTimeKey: "soon")
    XCTAssertTrue(makeAttachmentOptions(bad).isEmpty)
  }

  func testAttachingLeavesTheSourceFileInPlace() throws {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).png")
    try Data([0x89]).write(to: file)
    defer { try? FileManager.default.removeItem(at: file) }
    _ = try request(#"{"id":1,"title":"t","attachments":[{"id":"a","url":"\#(file.absoluteString)"}]}"#)
    XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
  }
}
