import UIKit
import Tauri
import ObjectiveC.runtime

#if ENABLE_PUSH_NOTIFICATIONS

enum AppDelegateSwizzler {
  static weak var plugin: NotificationPlugin?

  private static var originalIMPs: [Selector: IMP] = [:]
  private static var swizzled = false
  // Resetting `delegate` makes UIKit release the delegate UIApplicationMain created.
  private static var retainedDelegate: UIApplicationDelegate?

  /// Safe to call repeatedly: the app delegate may not be set yet the first time.
  static func swizzlePushCallbacks() {
    guard Thread.isMainThread else {
      DispatchQueue.main.async { swizzlePushCallbacks() }
      return
    }
    guard !swizzled else { return }
    guard let delegate = UIApplication.shared.delegate else { return }
    let cls: AnyClass = type(of: delegate)

    let installed = [
      install(
        cls,
        #selector(UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
        #selector(PushForwarder.ta_application(_:didRegisterForRemoteNotificationsWithDeviceToken:))
      ),
      install(
        cls,
        #selector(UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)),
        #selector(PushForwarder.ta_application(_:didFailToRegisterForRemoteNotificationsWithError:))
      ),
      install(
        cls,
        #selector(UIApplicationDelegate.application(_:didReceiveRemoteNotification:fetchCompletionHandler:)),
        #selector(PushForwarder.ta_application(_:didReceiveRemoteNotification:fetchCompletionHandler:))
      ),
    ]
    guard !installed.contains(false) else { return }
    swizzled = true

    // UIKit caches which delegate selectors exist at assignment, so reassign to pick up the new ones.
    retainedDelegate = delegate
    UIApplication.shared.delegate = nil
    UIApplication.shared.delegate = delegate
  }

  @discardableResult
  static func install(_ cls: AnyClass, _ selector: Selector, _ replacement: Selector) -> Bool {
    guard let replacementMethod = class_getInstanceMethod(PushForwarder.self, replacement) else { return false }
    // class_replaceMethod returns nil for an inherited implementation, so capture it first.
    let effectiveIMP = class_getInstanceMethod(cls, selector).map(method_getImplementation)
    let replacementIMP = method_getImplementation(replacementMethod)
    let ownIMP = class_replaceMethod(
      cls, selector, replacementIMP, method_getTypeEncoding(replacementMethod))
    // A retry after a partial install must not record the forwarder as the original.
    if ownIMP == replacementIMP { return true }
    originalIMPs[selector] = ownIMP ?? effectiveIMP
    return true
  }

  fileprivate static func callOriginalDidRegister(
    _ receiver: Any, _ selector: Selector, _ application: UIApplication, _ deviceToken: Data
  ) {
    guard let imp = originalIMPs[selector] else { return }
    typealias Fn = @convention(c) (Any, Selector, UIApplication, Data) -> Void
    unsafeBitCast(imp, to: Fn.self)(receiver, selector, application, deviceToken)
  }

  fileprivate static func callOriginalDidFail(
    _ receiver: Any, _ selector: Selector, _ application: UIApplication, _ error: Error
  ) {
    guard let imp = originalIMPs[selector] else { return }
    typealias Fn = @convention(c) (Any, Selector, UIApplication, Error) -> Void
    unsafeBitCast(imp, to: Fn.self)(receiver, selector, application, error)
  }

  fileprivate static func callOriginalDidReceive(
    _ receiver: Any, _ selector: Selector, _ application: UIApplication,
    _ userInfo: [AnyHashable: Any], _ completion: @escaping (UIBackgroundFetchResult) -> Void
  ) -> Bool {
    guard let imp = originalIMPs[selector] else { return false }
    typealias Fn = @convention(c) (Any, Selector, UIApplication, [AnyHashable: Any], @escaping (UIBackgroundFetchResult) -> Void) -> Void
    unsafeBitCast(imp, to: Fn.self)(receiver, selector, application, userInfo, completion)
    return true
  }
}

/// Hosts the implementations installed onto the app delegate class. At call time
/// `self` is the app delegate, so these may only touch params and static state.
final class PushForwarder: NSObject, UIApplicationDelegate {
  @objc func ta_application(_ application: UIApplication,
                            didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
    let rotated = AppDelegateSwizzler.plugin?.isRegisteringPush == false
    AppDelegateSwizzler.plugin?.handlePushTokenReceived(hex)
    AppDelegateSwizzler.plugin?.emit("push-token", ["token": hex, "rotated": rotated])
    AppDelegateSwizzler.callOriginalDidRegister(
      self,
      #selector(UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
      application,
      deviceToken
    )
  }

  @objc func ta_application(_ application: UIApplication,
                            didFailToRegisterForRemoteNotificationsWithError error: Error) {
    AppDelegateSwizzler.plugin?.handlePushTokenError(error)
    AppDelegateSwizzler.plugin?.emit("push-error", ["message": error.localizedDescription])
    AppDelegateSwizzler.callOriginalDidFail(
      self,
      #selector(UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)),
      application,
      error
    )
  }

  @objc func ta_application(_ application: UIApplication,
                            didReceiveRemoteNotification userInfo: [AnyHashable : Any],
                            fetchCompletionHandler completion: @escaping (UIBackgroundFetchResult) -> Void) {
    // Tauri cannot report whether a JS listener is subscribed, so any payload counts as handled.
    var handled = false
    if let jsData = JSTypes.coerceDictionaryToJSObject(userInfo) {
      AppDelegateSwizzler.plugin?.emit("push-message", jsData)
      handled = AppDelegateSwizzler.plugin != nil
    }
    let chained = AppDelegateSwizzler.callOriginalDidReceive(
      self,
      #selector(UIApplicationDelegate.application(_:didReceiveRemoteNotification:fetchCompletionHandler:)),
      application,
      userInfo,
      completion
    )
    if !chained {
      completion(handled ? .newData : .noData)
    }
  }
}

#endif
