import AppKit
import ObjectiveC.runtime

#if ENABLE_PUSH_NOTIFICATIONS

enum AppDelegateSwizzler {
  static weak var plugin: NotificationPlugin?

  private static var originalIMPs: [Selector: IMP] = [:]
  private static var swizzled = false

  /// Safe to call repeatedly: the app delegate may not be set yet the first time.
  static func swizzlePushCallbacks() {
    guard Thread.isMainThread else {
      DispatchQueue.main.async { swizzlePushCallbacks() }
      return
    }
    guard !swizzled else { return }
    guard let delegate = NSApplication.shared.delegate else { return }
    swizzled = true
    let cls: AnyClass = type(of: delegate)

    install(
      cls,
      #selector(NSApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
      #selector(PushForwarder.ta_application(_:didRegisterForRemoteNotificationsWithDeviceToken:))
    )
    install(
      cls,
      #selector(NSApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)),
      #selector(PushForwarder.ta_application(_:didFailToRegisterForRemoteNotificationsWithError:))
    )
    install(
      cls,
      #selector(NSApplicationDelegate.application(_:didReceiveRemoteNotification:)),
      #selector(PushForwarder.ta_application(_:didReceiveRemoteNotification:))
    )

    // AppKit caches which delegate selectors exist at assignment, so reassign to pick up the new ones.
    NSApplication.shared.delegate = nil
    NSApplication.shared.delegate = delegate
  }

  static func install(_ cls: AnyClass, _ selector: Selector, _ replacement: Selector) {
    guard let replacementMethod = class_getInstanceMethod(PushForwarder.self, replacement) else { return }
    // class_replaceMethod returns nil for an inherited implementation, so capture it first.
    let effectiveIMP = class_getInstanceMethod(cls, selector).map(method_getImplementation)
    let ownIMP = class_replaceMethod(
      cls, selector, method_getImplementation(replacementMethod), method_getTypeEncoding(replacementMethod))
    if let original = ownIMP ?? effectiveIMP {
      originalIMPs[selector] = original
    }
  }

  fileprivate static func callOriginalDidRegister(
    _ receiver: Any, _ selector: Selector, _ application: NSApplication, _ deviceToken: Data
  ) {
    guard let imp = originalIMPs[selector] else { return }
    typealias Fn = @convention(c) (Any, Selector, NSApplication, Data) -> Void
    unsafeBitCast(imp, to: Fn.self)(receiver, selector, application, deviceToken)
  }

  fileprivate static func callOriginalDidFail(
    _ receiver: Any, _ selector: Selector, _ application: NSApplication, _ error: Error
  ) {
    guard let imp = originalIMPs[selector] else { return }
    typealias Fn = @convention(c) (Any, Selector, NSApplication, Error) -> Void
    unsafeBitCast(imp, to: Fn.self)(receiver, selector, application, error)
  }

  fileprivate static func callOriginalDidReceive(
    _ receiver: Any, _ selector: Selector, _ application: NSApplication, _ userInfo: [String: Any]
  ) {
    guard let imp = originalIMPs[selector] else { return }
    typealias Fn = @convention(c) (Any, Selector, NSApplication, [String: Any]) -> Void
    unsafeBitCast(imp, to: Fn.self)(receiver, selector, application, userInfo)
  }
}

/// At call time `self` is the app delegate, so these may only touch params and static state.
final class PushForwarder: NSObject {
  @objc func ta_application(_ application: NSApplication,
                            didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    let hex = deviceToken.map { String(format: "%02x", $0) }.joined()

    AppDelegateSwizzler.plugin?.handlePushTokenReceived(hex)
    AppDelegateSwizzler.plugin?.emit("push-token", data: ["token": hex])

    AppDelegateSwizzler.callOriginalDidRegister(
      self,
      #selector(NSApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
      application,
      deviceToken
    )
  }

  @objc func ta_application(_ application: NSApplication,
                            didFailToRegisterForRemoteNotificationsWithError error: Error) {
    AppDelegateSwizzler.plugin?.handlePushTokenError(error)
    AppDelegateSwizzler.plugin?.emit("push-error", data: ["message": error.localizedDescription])

    AppDelegateSwizzler.callOriginalDidFail(
      self,
      #selector(NSApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)),
      application,
      error
    )
  }

  @objc func ta_application(_ application: NSApplication,
                            didReceiveRemoteNotification userInfo: [String: Any]) {
    AppDelegateSwizzler.plugin?.emitJSON("push-message", object: jsonCompatible(userInfo))

    AppDelegateSwizzler.callOriginalDidReceive(
      self,
      #selector(NSApplicationDelegate.application(_:didReceiveRemoteNotification:)),
      application,
      userInfo
    )
  }
}

#endif
