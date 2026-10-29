import XCTest
import AppKit
@testable import tauri_plugin_notifications

#if ENABLE_PUSH_NOTIFICATIONS

private class RecordingDelegate: NSObject, NSApplicationDelegate {
  static var received = [Data]()

  @objc dynamic func application(
    _ application: NSApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
  ) {
    RecordingDelegate.received.append(deviceToken)
  }
}

private class OwnImplementationDelegate: RecordingDelegate {
  static var ownReceived = [Data]()

  @objc dynamic override func application(
    _ application: NSApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
  ) {
    OwnImplementationDelegate.ownReceived.append(deviceToken)
  }
}

private class InheritingDelegate: RecordingDelegate {}

final class AppDelegateSwizzlerTests: XCTestCase {
  private let selector = #selector(
    NSApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:))
  private let replacement = #selector(
    PushForwarder.ta_application(_:didRegisterForRemoteNotificationsWithDeviceToken:))

  override func setUp() {
    RecordingDelegate.received = []
    OwnImplementationDelegate.ownReceived = []
  }

  func testChainsToTheDelegatesOwnImplementation() {
    AppDelegateSwizzler.install(OwnImplementationDelegate.self, selector, replacement)
    let delegate: NSApplicationDelegate = OwnImplementationDelegate()
    delegate.application?(NSApplication.shared, didRegisterForRemoteNotificationsWithDeviceToken: Data([1]))
    XCTAssertEqual(OwnImplementationDelegate.ownReceived, [Data([1])])
  }

  func testChainsToTheInheritedImplementation() {
    AppDelegateSwizzler.install(InheritingDelegate.self, selector, replacement)
    let delegate: NSApplicationDelegate = InheritingDelegate()
    delegate.application?(NSApplication.shared, didRegisterForRemoteNotificationsWithDeviceToken: Data([2]))
    XCTAssertEqual(RecordingDelegate.received, [Data([2])])
  }
}

#endif
