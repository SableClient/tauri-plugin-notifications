package app.tauri.notification

sealed class FcmTokenResult {
  data class Success(val token: String) : FcmTokenResult()

  /** Ran and failed. Unlike [Unavailable], triggers `push-error`. */
  data class Failed(val message: String) : FcmTokenResult()

  /** Firebase absent, or threw before the request ran. */
  data class Unavailable(val message: String) : FcmTokenResult()
}

sealed class FcmDeleteResult {
  object Deleted : FcmDeleteResult()
  data class Failed(val message: String) : FcmDeleteResult()

  /** No default FirebaseApp: nothing to delete. */
  object NotConfigured : FcmDeleteResult()
}
