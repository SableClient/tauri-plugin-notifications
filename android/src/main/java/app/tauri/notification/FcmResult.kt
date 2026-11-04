package app.tauri.notification

sealed class FcmDeleteResult {
  object Deleted : FcmDeleteResult()
  data class Failed(val message: String) : FcmDeleteResult()

  /** No default FirebaseApp: nothing to delete. */
  object NotConfigured : FcmDeleteResult()
}
