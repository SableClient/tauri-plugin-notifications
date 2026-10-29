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

final class AppDelegateSwizzlerTests: XCTestCase {
  private let selector = #selector(
    UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:))
  private let replacement = #selector(
    PushForwarder.ta_application(_:didRegisterForRemoteNotificationsWithDeviceToken:))

  override func setUp() {
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
}

#endif
