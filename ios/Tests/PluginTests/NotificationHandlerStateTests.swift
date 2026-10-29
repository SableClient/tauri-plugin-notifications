import XCTest
import UserNotifications
@testable import tauri_plugin_notifications

final class NotificationHandlerStateTests: XCTestCase {
  func testForgetDropsTheSavedNotification() throws {
    let handler = NotificationHandler()
    let notification = try JSONDecoder().decode(
      tauri_plugin_notifications.Notification.self, from: Data(#"{"id":1,"title":"t"}"#.utf8))
    handler.saveNotification("1", notification)
    let request = UNNotificationRequest(
      identifier: "1", content: UNMutableNotificationContent(), trigger: nil)
    XCTAssertNotNil(handler.toActiveNotification(request))

    handler.forget(["1"])

    XCTAssertNil(handler.toActiveNotification(request))
  }

  func testRollbackKeepsTheEarlierNotificationWithTheSameIdentifier() throws {
    let handler = NotificationHandler()
    func decode(_ sound: String) throws -> tauri_plugin_notifications.Notification {
      try JSONDecoder().decode(
        tauri_plugin_notifications.Notification.self,
        from: Data(#"{"id":1,"sound":"\#(sound)"}"#.utf8))
    }
    let request = UNNotificationRequest(
      identifier: "1", content: UNMutableNotificationContent(), trigger: nil)

    handler.saveNotification("1", try decode("first"))
    let second = handler.saveNotification("1", try decode("second"))
    handler.rollback(second)
    XCTAssertEqual(handler.toActiveNotification(request)?.sound, "first")

    let third = handler.saveNotification("1", try decode("third"))
    let fourth = handler.saveNotification("1", try decode("fourth"))
    handler.rollback(third)
    XCTAssertEqual(handler.toActiveNotification(request)?.sound, "fourth")
    handler.rollback(fourth)
    XCTAssertEqual(handler.toActiveNotification(request)?.sound, "first")
  }

  func testRollbackWithoutAnEarlierNotificationForgetsTheEntry() throws {
    let handler = NotificationHandler()
    let notification = try JSONDecoder().decode(
      tauri_plugin_notifications.Notification.self, from: Data(#"{"id":1,"title":"t"}"#.utf8))
    let request = UNNotificationRequest(
      identifier: "1", content: UNMutableNotificationContent(), trigger: nil)
    handler.rollback(handler.saveNotification("1", notification))
    XCTAssertNil(handler.toActiveNotification(request))
  }

  func testProvisionalAndEphemeralGrantsAllowDelivery() {
    XCTAssertTrue(NotificationHandler.allowsDelivery(.authorized))
    XCTAssertTrue(NotificationHandler.allowsDelivery(.provisional))
    XCTAssertTrue(NotificationHandler.allowsDelivery(.ephemeral))
    XCTAssertFalse(NotificationHandler.allowsDelivery(.denied))
    XCTAssertFalse(NotificationHandler.allowsDelivery(.notDetermined))
  }
}
