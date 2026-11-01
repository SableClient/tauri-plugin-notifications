import UserNotifications

private let categoryQueue = DispatchQueue(label: "tauri-plugin-notifications.categories")

func buildCategories(_ actionTypes: [ActionType]) -> [UNNotificationCategory] {
  let generalCategory = UNNotificationCategory(
    identifier: "GENERAL",
    actions: [],
    intentIdentifiers: [],
    options: .customDismissAction)

  return [generalCategory]
    + actionTypes.map { type in
      UNNotificationCategory(
        identifier: type.id,
        actions: makeActions(type.actions),
        intentIdentifiers: [],
        hiddenPreviewsBodyPlaceholder: type.hiddenPreviewsBodyPlaceholder
          ?? type.hiddenBodyPlaceholder ?? "",
        options: makeCategoryOptions(type))
    }
}

func mergeCategories(
  _ existing: Set<UNNotificationCategory>, _ added: [UNNotificationCategory]
) -> Set<UNNotificationCategory> {
  let replaced = Set(added.map(\.identifier))
  return existing.filter { !replaced.contains($0.identifier) }.union(added)
}

internal func makeCategories(_ actionTypes: [ActionType]) {
  let created = buildCategories(actionTypes)
  let center = UNUserNotificationCenter.current()
  categoryQueue.async {
    let semaphore = DispatchSemaphore(value: 0)
    var existing = Set<UNNotificationCategory>()
    center.getNotificationCategories {
      existing = $0
      semaphore.signal()
    }
    semaphore.wait()
    center.setNotificationCategories(mergeCategories(existing, created))
  }
}

func makeActions(_ actions: [Action]) -> [UNNotificationAction] {
  var createdActions = [UNNotificationAction]()

  for action in actions {
    var newAction: UNNotificationAction
    if action.input ?? false {
      if action.inputButtonTitle != nil {
        newAction = UNTextInputNotificationAction(
          identifier: action.id,
          title: action.title,
          options: makeActionOptions(action),
          textInputButtonTitle: action.inputButtonTitle ?? "",
          textInputPlaceholder: action.inputPlaceholder ?? "")
      } else {
        newAction = UNTextInputNotificationAction(
          identifier: action.id, title: action.title, options: makeActionOptions(action))
      }
    } else {
      // Create the custom actions for the TIMER_EXPIRED category.
      newAction = UNNotificationAction(
        identifier: action.id,
        title: action.title,
        options: makeActionOptions(action))
    }
    createdActions.append(newAction)
  }

  return createdActions
}

func makeActionOptions(_ action: Action) -> UNNotificationActionOptions {
  var options: UNNotificationActionOptions = []
  if action.foreground ?? false {
    options.insert(.foreground)
  }
  if action.destructive ?? false {
    options.insert(.destructive)
  }
  if action.requiresAuthentication ?? false {
    options.insert(.authenticationRequired)
  }
  return options
}

func makeCategoryOptions(_ type: ActionType) -> UNNotificationCategoryOptions {
  var options: UNNotificationCategoryOptions = []
  if type.customDismissAction ?? false {
    options.insert(.customDismissAction)
  }
  if type.hiddenPreviewsShowTitle ?? false {
    options.insert(.hiddenPreviewsShowTitle)
  }
  if type.hiddenPreviewsShowSubtitle ?? false {
    options.insert(.hiddenPreviewsShowSubtitle)
  }
  return options
}
