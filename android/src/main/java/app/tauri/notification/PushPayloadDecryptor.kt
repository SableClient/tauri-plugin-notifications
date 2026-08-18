package app.tauri.notification

import android.content.Context
import android.util.Log

/**
 * Decrypts a push payload without a webview. The host app supplies this by exporting the
 * JNI symbol from its native library; builds that do not simply skip decryption.
 */
internal object PushPayloadDecryptor {
    private const val TAG = "PushPayloadDecryptor"

    /** Tauri names the app's Rust library `app_lib` by convention. */
    private const val HOST_LIBRARY = "app_lib"

    private var loaded: Boolean? = null

    private external fun nativeDecryptPush(
        storeDir: String,
        userId: String,
        deviceId: String,
        roomId: String,
        eventJson: String,
    ): String?

    private fun available(): Boolean {
        loaded?.let { return it }

        val result = try {
            System.loadLibrary(HOST_LIBRARY)
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.i(TAG, "No native decryptor in this build: ${error.message}")
            false
        }
        loaded = result
        return result
    }

    /** The clear event as JSON, or null when it cannot be decrypted. */
    fun decrypt(
        context: Context,
        userId: String,
        deviceId: String,
        roomId: String,
        eventJson: String,
    ): String? {
        if (!available()) return null

        return try {
            nativeDecryptPush(
                // Tauri resolves its app data dir to dataDir, not filesDir.
                context.dataDir.absolutePath,
                userId,
                deviceId,
                roomId,
                eventJson,
            ).takeIf { !it.isNullOrEmpty() }
        } catch (error: Throwable) {
            // Expected while the Megolm key is still in flight, so not an error.
            Log.i(TAG, "Push payload not decryptable: ${error.message}")
            null
        }
    }
}
