package app.tauri.notification

import android.content.Context
import android.content.pm.PackageManager

interface PushDelegate {
    fun isActivation(payload: String): Boolean
    /** [encrypted] is false for a plain push, whose content anyone who knows the endpoint can forge. */
    fun render(context: Context, payload: String, encrypted: Boolean = true)
    fun schedule(context: Context, payload: String, encrypted: Boolean = true)
    fun endpointChanged(context: Context, endpoint: String, p256dh: String?, auth: String?)
    fun endpointChanged(context: Context, instance: String, endpoint: String, p256dh: String?, auth: String?) =
        endpointChanged(context, endpoint, p256dh, auth)
    fun record(context: Context, outcome: String)
}

object NoPushDelegate : PushDelegate {
    override fun isActivation(payload: String) = false
    override fun render(context: Context, payload: String, encrypted: Boolean) {}
    override fun schedule(context: Context, payload: String, encrypted: Boolean) {}
    override fun endpointChanged(context: Context, endpoint: String, p256dh: String?, auth: String?) {}
    override fun record(context: Context, outcome: String) {}
}

object PushDelegates {
    const val META_DATA_KEY = "app.tauri.notification.PUSH_DELEGATE"

    @Volatile
    private var resolved: PushDelegate? = null

    fun get(context: Context): PushDelegate {
        resolved?.let { return it }
        return synchronized(this) { resolved ?: load(context).also { resolved = it } }
    }

    internal fun override(delegate: PushDelegate?) {
        resolved = delegate
    }

    private fun load(context: Context): PushDelegate {
        val name = runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(META_DATA_KEY)
        }.getOrNull() ?: return NoPushDelegate
        return runCatching { Class.forName(name).getDeclaredConstructor().newInstance() as PushDelegate }
            .getOrElse { error -> throw IllegalStateException("Cannot load push delegate $name", error) }
    }
}
