package app.tauri.notification

import android.content.Intent
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TauriNotificationManagerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun nextDailyTriggerKeepsTheTimeOfDay() {
        val day = 24L * 60 * 60 * 1000
        val from = 1_000_000L

        assertEquals(from, nextDailyTrigger(from, from - 1))
        assertEquals(from + day, nextDailyTrigger(from, from))
        assertEquals(from + 3 * day, nextDailyTrigger(from, from + 2 * day + 5))
    }

    @Test
    fun actionIntentsOfTheSameNotificationAreDistinguishable() {
        val manager = TauriNotificationManager(NotificationStorage(context, ObjectMapper()), null, context, null)
        val build = TauriNotificationManager::class.java
            .getDeclaredMethod("buildIntent", Notification::class.java, String::class.java)
            .apply { isAccessible = true }
        val notification = Notification().apply { id = 7 }

        val open = build.invoke(manager, notification, "tap") as Intent
        val reply = build.invoke(manager, notification, "reply") as Intent

        assertEquals("7/tap", open.identifier)
        assertTrue(open.filterEquals(reply).not())
    }
}
