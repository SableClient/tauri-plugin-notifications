package app.tauri.notification

sealed class FcmTokenResult {
  data class Success(val token: String) : FcmTokenResult()

  /** The request ran and failed. Unlike [Unavailable], this triggers `push-error`. */
  data class Failed(val message: String) : FcmTokenResult()

  /** Firebase is absent or threw before the request ran. */
  data class Unavailable(val message: String) : FcmTokenResult()
}

sealed class FcmDeleteResult {
  object Deleted : FcmDeleteResult()
  data class Failed(val message: String) : FcmDeleteResult()

  /** No default FirebaseApp, so there was nothing to delete. */
  object NotConfigured : FcmDeleteResult()
}
