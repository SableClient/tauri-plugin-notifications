package app.tauri.notification

import android.content.Context
import org.json.JSONObject

class RecordingPushDelegate : PushDelegate {
    val rendered = mutableListOf<String>()
    val scheduled = mutableListOf<String>()
    private val outcomes = mutableMapOf<String, Int>()
    var scheduleFailure: Exception? = null
    var onRender: () -> Unit = {}

    override fun isActivation(payload: String): Boolean =
        runCatching { JSONObject(payload).optString("ack_token").isNotEmpty() }.getOrDefault(false)

    override fun render(context: Context, payload: String) {
        onRender()
        rendered.add(payload)
    }

    override fun schedule(context: Context, payload: String) {
        scheduleFailure?.let { throw it }
        scheduled.add(payload)
    }

    override fun endpointChanged(context: Context, endpoint: String, p256dh: String?, auth: String?) {}

    override fun record(context: Context, outcome: String) {
        outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
    }

    fun drain(): Map<String, Int> = outcomes.toMap().also { outcomes.clear() }
}
