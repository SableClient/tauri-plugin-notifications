package app.tauri.notification

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * WebPush subscription keys for the in-app websocket transport. The connector's key
 * store cannot be used: its `keys` table has a foreign key onto `registrations`.
 */
internal object EmbeddedWebPushKeys {
    private const val TAG = "EmbeddedWebPushKeys"
    private const val LEGACY_PREFS = "embedded-webpush-keys"
    private const val FILE_NAME = "embedded-webpush-keys.json"
    private const val KEY_PRIVATE = "private"
    private const val KEY_PUBLIC = "public"
    private const val KEY_AUTH = "auth"

    private const val CURVE = "secp256r1"
    private const val AUTH_SECRET_BYTES = 16
    private const val COORDINATE_BYTES = 32
    private const val POINT_BYTES = 1 + 2 * COORDINATE_BYTES

    /** What a homeserver needs to encrypt to us, base64url unpadded. */
    data class PublicKeys(val p256dh: String, val auth: String)

    private class Stored(val private: ByteArray, val public: ByteArray, val auth: ByteArray)

    private val lock = Any()
    private var cached: Pair<File, Stored>? = null

    internal fun forgetCachedKeys() = synchronized(lock) { cached = null }

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
        } catch (error: GeneralSecurityException) {
            null
        }
    }

    /**
     * Keys live under noBackupFilesDir: a restored device would otherwise hold a private key
     * for a subscription the homeserver never learned about.
     */
    private fun ensure(context: Context): Stored? = synchronized(lock) {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        cached?.takeIf { it.first == file }?.let { return it.second }

        val stored = read(file) ?: migrateLegacy(context, file) ?: generate(file)
        if (stored != null) cached = file to stored
        stored
    }

    private fun read(file: File): Stored? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            parse(json.getString(KEY_PRIVATE), json.getString(KEY_PUBLIC), json.getString(KEY_AUTH))
        } catch (error: IOException) {
            Log.w(TAG, "Could not read the stored push keys; generating new ones", error)
            null
        } catch (error: JSONException) {
            Log.w(TAG, "Stored push keys are corrupt; generating new ones", error)
            null
        }
    }

    private fun parse(private: String, public: String, auth: String): Stored? = try {
        Stored(decode(private), decode(public), decode(auth)).takeIf {
            it.public.size == POINT_BYTES && it.auth.size == AUTH_SECRET_BYTES && it.private.isNotEmpty()
        }
    } catch (error: IllegalArgumentException) {
        null
    }

    private fun migrateLegacy(context: Context, file: File): Stored? {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val private = prefs.getString(KEY_PRIVATE, null)
        val public = prefs.getString(KEY_PUBLIC, null)
        val auth = prefs.getString(KEY_AUTH, null)
        if (private == null || public == null || auth == null) return null

        val stored = parse(private, public, auth) ?: return null
        if (!write(file, stored)) return null
        prefs.edit().clear().apply()
        return stored
    }

    private fun generate(file: File): Stored? {
        val stored = try {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            val pair = generator.generateKeyPair()
            val secret = ByteArray(AUTH_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
            Stored(pair.private.encoded, uncompressedPoint(pair.public as ECPublicKey), secret)
        } catch (error: GeneralSecurityException) {
            Log.w(TAG, "Could not generate push keys", error)
            return null
        }
        return stored.takeIf { write(file, it) }
    }

    private fun write(file: File, stored: Stored): Boolean {
        val atomic = AtomicFile(file)
        val out = try {
            atomic.startWrite()
        } catch (error: IOException) {
            Log.w(TAG, "Could not store the push keys", error)
            return false
        }
        return try {
            val json = JSONObject()
                .put(KEY_PRIVATE, encode(stored.private))
                .put(KEY_PUBLIC, encode(stored.public))
                .put(KEY_AUTH, encode(stored.auth))
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
            true
        } catch (error: IOException) {
            atomic.failWrite(out)
            Log.w(TAG, "Could not store the push keys", error)
            false
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
