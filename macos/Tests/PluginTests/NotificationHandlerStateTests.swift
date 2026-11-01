import XCTest
import UserNotifications
@testable import tauri_plugin_notifications

final class NotificationHandlerStateTests: XCTestCase {
  func testProvisionalAndEphemeralGrantsAllowDelivery() {
    XCTAssertTrue(NotificationHandler.allowsDelivery(.authorized))
    XCTAssertTrue(NotificationHandler.allowsDelivery(.provisional))
    XCTAssertFalse(NotificationHandler.allowsDelivery(.denied))
    XCTAssertFalse(NotificationHandler.allowsDelivery(.notDetermined))
  }
}

final class PushPayloadTests: XCTestCase {
  func testNestedPayloadsKeepTheirStructure() throws {
    let payload: [String: Any] = [
      "aps": ["alert": ["title": "t"], "badge": 2],
      "tags": ["a", "b"],
      "flag": NSNull(),
    ]
    let json = try JSONSerialization.data(withJSONObject: jsonCompatible(payload))
    let decoded = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
    let aps = try XCTUnwrap(decoded["aps"] as? [String: Any])
    XCTAssertEqual((aps["alert"] as? [String: String])?["title"], "t")
    XCTAssertEqual(aps["badge"] as? Int, 2)
    XCTAssertEqual(decoded["tags"] as? [String], ["a", "b"])
  }

  func testNonJsonValuesAreStringified() {
    XCTAssertTrue(JSONSerialization.isValidJSONObject(jsonCompatible(["when": Date(timeIntervalSince1970: 0)])))
  }
}

final class ReceivedNotificationExtraTests: XCTestCase {
  func testExtraKeepsNumericValuesAndSkipsAps() {
    let extra = NotificationHandler.notificationExtra([
      "aps": ["alert": "x"], "room_id": "!r:example.org", "count": NSNumber(value: 3),
    ])
    XCTAssertEqual(extra?["room_id"], "!r:example.org")
    XCTAssertEqual(extra?["count"], "3")
    XCTAssertNil(extra?["aps"])
  }
}
