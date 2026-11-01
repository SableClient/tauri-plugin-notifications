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
}
