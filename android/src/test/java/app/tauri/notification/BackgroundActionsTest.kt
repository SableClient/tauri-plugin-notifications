package app.tauri.notification

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackgroundActionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun action(input: Boolean?, foreground: Boolean?) = NotificationAction().also {
        it.id = "mark-read"
        it.input = input
        it.foreground = foreground
    }

    @Test
    fun onlyAPlainNonForegroundActionRunsInBackground() {
        assertTrue(BackgroundActions.runsInBackground(action(input = false, foreground = false)))
        assertTrue(BackgroundActions.runsInBackground(action(input = null, foreground = false)))
        assertFalse(BackgroundActions.runsInBackground(action(input = true, foreground = false)))
        assertFalse(BackgroundActions.runsInBackground(action(input = false, foreground = true)))
        assertFalse(BackgroundActions.runsInBackground(action(input = false, foreground = null)))
    }

    @Test
    fun payloadCarriesTheActionAndTheNotification() {
        val intent = Intent()
            .putExtra(ACTION_INTENT_KEY, "mark-read")
            .putExtra(NOTIFICATION_OBJ_INTENT_KEY, """{"id":7,"extra":{"room_id":"!r:x"}}""")

        val data = BackgroundActions.payload(intent)

        assertEquals("mark-read", data.getString("actionId"))
        assertEquals("!r:x", data.getJSObject("notification")!!.getJSObject("extra")!!.getString("room_id"))
    }

    @Test
    fun queuedActionsSurviveUntilTheyAreDrainedOnce() {
        BackgroundActions.drain(context)
        BackgroundActions.queue(context, BackgroundActions.payload(Intent().putExtra(ACTION_INTENT_KEY, "a")))
        BackgroundActions.queue(context, BackgroundActions.payload(Intent().putExtra(ACTION_INTENT_KEY, "b")))

        assertEquals(listOf("a", "b"), BackgroundActions.drain(context).map { it.getString("actionId") })
        assertTrue(BackgroundActions.drain(context).isEmpty())
    }
}
