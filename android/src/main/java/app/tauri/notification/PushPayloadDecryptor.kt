package app.tauri.notification

import android.content.Context
import android.util.Log

internal sealed interface PushDecryptResult {
    data class Success(val clearEventJson: String) : PushDecryptResult
    data class Failure(val outcome: PushOutcome) : PushDecryptResult
}

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

    fun decrypt(
        context: Context,
        userId: String,
        deviceId: String,
        roomId: String,
        eventJson: String,
    ): PushDecryptResult {
        if (!available()) return PushDecryptResult.Failure(PushOutcome.NO_NATIVE_LIB)

        val clear = try {
            nativeDecryptPush(
                // Tauri resolves its app data dir to dataDir, not filesDir.
                context.dataDir.absolutePath,
                userId,
                deviceId,
                roomId,
                eventJson,
            )
        } catch (error: Throwable) {
            // Expected while the Megolm key is still in flight, so not an error.
            Log.i(TAG, "Push payload not decryptable: ${error.message}")
            null
        }

        return clear?.takeIf { it.isNotEmpty() }
            ?.let { PushDecryptResult.Success(it) }
            ?: PushDecryptResult.Failure(PushOutcome.DECRYPT_FAILED)
    }
}
