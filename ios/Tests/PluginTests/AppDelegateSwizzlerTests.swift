import XCTest
import UIKit
@testable import tauri_plugin_notifications

#if ENABLE_PUSH_NOTIFICATIONS

private class RecordingDelegate: NSObject, UIApplicationDelegate {
  static var received = [Data]()

  @objc dynamic func application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
  ) {
    RecordingDelegate.received.append(deviceToken)
  }
}

private class OwnImplementationDelegate: RecordingDelegate {
  static var ownReceived = [Data]()

  @objc dynamic override func application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
  ) {
    OwnImplementationDelegate.ownReceived.append(deviceToken)
  }
}

private class InheritingDelegate: RecordingDelegate {}

private class FailureDelegate: NSObject, UIApplicationDelegate {
  static var received = [String]()

  @objc dynamic func application(
    _ application: UIApplication,
    didFailToRegisterForRemoteNotificationsWithError error: Error
  ) {
    FailureDelegate.received.append(error.localizedDescription)
  }
}

private class FetchDelegate: NSObject, UIApplicationDelegate {
  static var received = [String]()

  @objc dynamic func application(
    _ application: UIApplication,
    didReceiveRemoteNotification userInfo: [AnyHashable: Any],
    fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
  ) {
    FetchDelegate.received.append(userInfo["id"] as? String ?? "")
    completionHandler(.failed)
  }
}

private class BareDelegate: NSObject, UIApplicationDelegate {}

final class AppDelegateSwizzlerTests: XCTestCase {
  private let selector = #selector(
    UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:))
  private let replacement = #selector(
    PushForwarder.ta_application(_:didRegisterForRemoteNotificationsWithDeviceToken:))

  private let failSelector = #selector(
    UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:))
  private let failReplacement = #selector(
    PushForwarder.ta_application(_:didFailToRegisterForRemoteNotificationsWithError:))
  private let fetchSelector = #selector(
    UIApplicationDelegate.application(_:didReceiveRemoteNotification:fetchCompletionHandler:))
  private let fetchReplacement = #selector(
    PushForwarder.ta_application(_:didReceiveRemoteNotification:fetchCompletionHandler:))

  override func setUp() {
    FailureDelegate.received = []
    FetchDelegate.received = []
    RecordingDelegate.received = []
    OwnImplementationDelegate.ownReceived = []
  }

  func testChainsToTheDelegatesOwnImplementation() {
    AppDelegateSwizzler.install(OwnImplementationDelegate.self, selector, replacement)
    let delegate: UIApplicationDelegate = OwnImplementationDelegate()
    delegate.application?(UIApplication.shared, didRegisterForRemoteNotificationsWithDeviceToken: Data([1]))
    XCTAssertEqual(OwnImplementationDelegate.ownReceived, [Data([1])])
  }

  func testChainsToTheInheritedImplementation() {
    AppDelegateSwizzler.install(InheritingDelegate.self, selector, replacement)
    let delegate: UIApplicationDelegate = InheritingDelegate()
    delegate.application?(UIApplication.shared, didRegisterForRemoteNotificationsWithDeviceToken: Data([2]))
    XCTAssertEqual(RecordingDelegate.received, [Data([2])])
  }

  func testChainsRegistrationFailureToTheDelegatesOwnImplementation() {
    AppDelegateSwizzler.install(FailureDelegate.self, failSelector, failReplacement)
    let delegate: UIApplicationDelegate = FailureDelegate()
    let error = NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "no apns"])
    delegate.application?(UIApplication.shared, didFailToRegisterForRemoteNotificationsWithError: error)
    XCTAssertEqual(FailureDelegate.received, ["no apns"])
  }

  func testChainsRemoteNotificationAndPassesTheOriginalCompletionHandler() {
    AppDelegateSwizzler.install(FetchDelegate.self, fetchSelector, fetchReplacement)
    let delegate: UIApplicationDelegate = FetchDelegate()
    var results = [UIBackgroundFetchResult]()
    delegate.application?(
      UIApplication.shared, didReceiveRemoteNotification: ["id": "a"],
      fetchCompletionHandler: { results.append($0) })
    XCTAssertEqual(FetchDelegate.received, ["a"])
    XCTAssertEqual(results, [.failed])
  }

  func testCompletesTheRemoteNotificationItselfWhenTheDelegateHasNoImplementation() {
    AppDelegateSwizzler.install(FetchDelegate.self, fetchSelector, fetchReplacement)
    AppDelegateSwizzler.install(BareDelegate.self, fetchSelector, fetchReplacement)
    let delegate: UIApplicationDelegate = BareDelegate()
    var results = [UIBackgroundFetchResult]()
    delegate.application?(
      UIApplication.shared, didReceiveRemoteNotification: ["id": "b"],
      fetchCompletionHandler: { results.append($0) })
    XCTAssertEqual(results, [.noData])
    XCTAssertEqual(FetchDelegate.received, [])
  }
}

#endif
