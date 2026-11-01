import XCTest
import UserNotifications
@testable import tauri_plugin_notifications

private func decodeNotification(_ json: String) throws -> PluginNotification {
  try JSONDecoder().decode(PluginNotification.self, from: Data(json.utf8))
}

private func request(
  _ id: String, content: UNNotificationContent = UNMutableNotificationContent()
) -> UNNotificationRequest {
  UNNotificationRequest(identifier: id, content: content, trigger: nil)
}

final class OptionCombiningTests: XCTestCase {
  func testActionOptionsAreCombined() {
    let action = Action(
      id: "a", title: "A", requiresAuthentication: true, foreground: true, destructive: true,
      input: nil, inputButtonTitle: nil, inputPlaceholder: nil)
    XCTAssertEqual(makeActionOptions(action), [.foreground, .destructive, .authenticationRequired])
  }

  func testCategoryOptionsAreCombined() {
    let type = ActionType(
      id: "t", actions: [], hiddenPreviewsBodyPlaceholder: nil, customDismissAction: true,
      allowInCarPlay: true, hiddenPreviewsShowTitle: true, hiddenPreviewsShowSubtitle: true,
      hiddenBodyPlaceholder: nil)
    XCTAssertEqual(
      makeCategoryOptions(type),
      [.customDismissAction, .hiddenPreviewsShowTitle, .hiddenPreviewsShowSubtitle])
  }

  func testBuildCategoriesUsesHiddenPreviewsBodyPlaceholder() {
    let type = ActionType(
      id: "t", actions: [], hiddenPreviewsBodyPlaceholder: "hidden", customDismissAction: nil,
      allowInCarPlay: nil, hiddenPreviewsShowTitle: nil, hiddenPreviewsShowSubtitle: nil,
      hiddenBodyPlaceholder: nil)
    let category = buildCategories([type]).first { $0.identifier == "t" }
    XCTAssertEqual(category?.hiddenPreviewsBodyPlaceholder, "hidden")
  }

  func testMergeCategoriesKeepsOthersAndReplacesSameIdentifier() {
    let kept = UNNotificationCategory(identifier: "kept", actions: [], intentIdentifiers: [], options: [])
    let old = UNNotificationCategory(identifier: "same", actions: [], intentIdentifiers: [], options: [])
    let new = UNNotificationCategory(
      identifier: "same", actions: [], intentIdentifiers: [], options: .customDismissAction)
    let merged = mergeCategories([kept, old], [new])
    XCTAssertEqual(Set(merged.map(\.identifier)), ["kept", "same"])
    XCTAssertEqual(merged.first { $0.identifier == "same" }?.options, .customDismissAction)
  }
}

final class DecodingAndContentTests: XCTestCase {
  func testNullTitleAndMixedExtraDecode() throws {
    let notification = try decodeNotification(
      #"{"id":1,"title":null,"extra":{"s":"v","n":3,"f":1.5,"b":true,"z":null,"a":[1],"o":{"k":"v"}}}"#)
    XCTAssertNil(notification.title)
    XCTAssertEqual(notification.extra?["n"], JSONValue.integer(3))
    XCTAssertEqual(notification.extra?["f"], JSONValue.double(1.5))
    XCTAssertEqual(notification.extra?["b"], JSONValue.bool(true))
    XCTAssertEqual(notification.extra?["z"], JSONValue.null)
  }

  func testMissingTitleProducesEmptyContentTitle() throws {
    let content = try makeNotificationContent(try decodeNotification(#"{"id":1}"#))
    XCTAssertEqual(content.title, "")
  }

  func testAbsolutePathAttachmentUrlIsAFileUrl() {
    let url = makeAttachmentUrl("/tmp/some image.png")
    XCTAssertEqual(url?.isFileURL, true)
    XCTAssertEqual(url?.path, "/tmp/some image.png")
  }
}

final class ScheduleDateParsingTests: XCTestCase {
  private let expected = Date(timeIntervalSince1970: 1_893_492_000)

  func testParsesRustIso8601Variants() {
    let variants = [
      "2030-01-01T10:00:00Z",
      "2030-01-01T10:00:00.000Z",
      "2030-01-01T10:00:00.000000000Z",
      "2030-01-01T10:00:00+00:00",
      "2030-01-01T12:00:00.000000000+02:00",
      "2030-01-01T05:30:00-04:30",
    ]
    for variant in variants {
      XCTAssertEqual(
        parseScheduleDate(variant)?.timeIntervalSince1970 ?? 0, expected.timeIntervalSince1970,
        accuracy: 0.001, variant)
    }
  }

  func testRejectsGarbage() {
    XCTAssertNil(parseScheduleDate("tomorrow"))
  }
}

final class LocalOriginTests: XCTestCase {
  private func content(_ json: String) throws -> UNNotificationContent {
    try makeNotificationContent(try decodeNotification(json))
  }

  func testMarkerRoundTripsSilentSoundAndAttachments() throws {
    let content = try content(
      #"{"id":7,"title":"t","silent":true,"sound":"ping","attachments":[{"id":"a","url":"file:///x.png"}]}"#)
    let origin = try XCTUnwrap(NotificationHandler.localOrigin(content.userInfo))
    XCTAssertEqual(origin.silent, true)
    XCTAssertEqual(origin.sound, "ping")
    XCTAssertEqual(origin.attachments?.first?.url, "file:///x.png")
  }

  func testActiveAndPendingAreRebuiltFromTheRequest() throws {
    let content = try content(
      #"{"id":7,"title":"t","sound":"ping","schedule":{"every":{"interval":"day","count":1}}}"#)
    let handler = NotificationHandler()
    let req = request("7", content: content)
    XCTAssertEqual(handler.toActiveNotification(req)?.sound, "ping")
    XCTAssertEqual(handler.toPendingNotification(req)?.id, 7)
  }

  func testRequestWithoutMarkerIsNotLocal() {
    let handler = NotificationHandler()
    XCTAssertNil(NotificationHandler.localOrigin(UNMutableNotificationContent().userInfo))
    XCTAssertNil(handler.toActiveNotification(request("9")))
    XCTAssertNil(handler.toPendingNotification(request("9")))
    XCTAssertEqual(handler.toRemoteActionNotification(request("9")).source, "push")
  }

  func testMarkerKeyIsNotReportedAsExtra() throws {
    let content = try content(#"{"id":1,"title":"t","extra":{"k":"v"}}"#)
    XCTAssertNotNil(content.userInfo[localOriginKey] as? String)
    XCTAssertEqual(NotificationHandler.notificationExtra(content.userInfo), ["k": "v"])
  }
}

final class JsonCompatibleTests: XCTestCase {
  func testNonFiniteNumbersDoNotInvalidateThePayload() throws {
    let payload: [String: Any] = [
      "ok": 1, "nan": NSNumber(value: Double.nan), "inf": NSNumber(value: Double.infinity),
    ]
    let converted = jsonCompatible(payload)
    XCTAssertTrue(JSONSerialization.isValidJSONObject(converted))
    let dictionary = try XCTUnwrap(converted as? [String: Any])
    XCTAssertEqual(dictionary["ok"] as? Int, 1)
    XCTAssertTrue(dictionary["nan"] is NSNull)
    XCTAssertTrue(dictionary["inf"] is NSNull)
  }
}
