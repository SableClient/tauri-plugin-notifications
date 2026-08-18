package app.tauri.notification

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * WebPush subscription keys for the in-app websocket transport.
 *
 * The connector's key store cannot be used: its `keys` table has a foreign key onto
 * `registrations`, and this transport has no distributor registration.
 */
internal object EmbeddedWebPushKeys {
    private const val PREFS = "embedded-webpush-keys"
    private const val KEY_PRIVATE = "private"
    private const val KEY_PUBLIC = "public"
    private const val KEY_AUTH = "auth"

    private const val CURVE = "secp256r1"
    private const val AUTH_SECRET_BYTES = 16
    private const val COORDINATE_BYTES = 32

    /** What a homeserver needs to encrypt to us, base64url unpadded. */
    data class PublicKeys(val p256dh: String, val auth: String)

    fun publicKeys(context: Context): PublicKeys? {
        val stored = ensure(context) ?: return null
        return PublicKeys(encode(stored.public), encode(stored.auth))
    }

    /** Null when the body was not encrypted to this subscription. */
    fun decrypt(context: Context, sealed: ByteArray): ByteArray? {
        val stored = ensure(context) ?: return null
        return try {
            WebPushHybridDecrypt.Builder()
                .withAuthSecret(stored.auth)
                .withRecipientPublicKey(stored.public)
                .withRecipientPrivateKey(privateScalar(stored.private))
                .build()
                .decrypt(sealed, null)
        } catch (_: Exception) {
            null
        }
    }

    private class Stored(val private: ByteArray, val public: ByteArray, val auth: ByteArray)

    private fun ensure(context: Context): Stored? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val private = prefs.getString(KEY_PRIVATE, null)
        val public = prefs.getString(KEY_PUBLIC, null)
        val auth = prefs.getString(KEY_AUTH, null)
        if (private != null && public != null && auth != null) {
            return Stored(decode(private), decode(public), decode(auth))
        }

        return try {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            val pair = generator.generateKeyPair()

            val point = uncompressedPoint(pair.public as ECPublicKey)
            val secret = ByteArray(AUTH_SECRET_BYTES).also { SecureRandom().nextBytes(it) }

            prefs.edit()
                .putString(KEY_PRIVATE, encode(pair.private.encoded))
                .putString(KEY_PUBLIC, encode(point))
                .putString(KEY_AUTH, encode(secret))
                .apply()

            Stored(pair.private.encoded, point, secret)
        } catch (_: Exception) {
            null
        }
    }

    /** Tink takes the raw scalar, not the PKCS#8 encoding we persist. */
    private fun privateScalar(pkcs8: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey
        return fixedWidth(key.s)
    }

    /** WebPush carries the point as 0x04 ‖ X ‖ Y, each coordinate fixed at 32 bytes. */
    private fun uncompressedPoint(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixedWidth(key.w.affineX) + fixedWidth(key.w.affineY)

    private fun fixedWidth(value: BigInteger): ByteArray {
        // BigInteger adds a sign byte and drops leading zeroes; both break the fixed width.
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        if (raw.size >= COORDINATE_BYTES) {
            return raw.copyOfRange(raw.size - COORDINATE_BYTES, raw.size)
        }
        return ByteArray(COORDINATE_BYTES - raw.size) + raw
    }

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
