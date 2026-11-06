package app.tauri.notification

import android.app.Activity
import app.tauri.PermissionState
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.Date
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPluginTest {
    @Test
    fun unregisterEmitsAPushUnregisteredEvent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val plugin = spyk(NotificationPlugin(activity))
        every { plugin.trigger(any(), any<JSObject>()) } just Runs

        plugin.onUnifiedPushUnregistered("acct")

        verify {
            plugin.trigger("push-unregistered", match<JSObject> {
                it.getString("provider") == "unifiedpush" && it.getString("instance") == "acct"
            })
        }
    }

    @Test
    fun showRejectsAScheduleInThePast() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val plugin = NotificationPlugin(activity)
        val notification = Notification().apply {
            id = 1
            schedule = NotificationSchedule.At().apply { date = Date(System.currentTimeMillis() - 60_000) }
        }
        val invoke = mockk<Invoke>(relaxed = true)
        every { invoke.parseArgs(Notification::class.java) } returns notification
        every { invoke.getRawArgs() } returns "{}"

        plugin.show(invoke)

        verify { invoke.reject(any<String>()) }
        verify(exactly = 0) { invoke.resolveObject(any()) }
        assertTrue(NotificationStorage(activity, ObjectMapper()).getSavedNotificationIds().isEmpty())
    }

    @Test
    fun requestPermissionsResolvesWhenAlreadyGranted() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Permission state comes from the Tauri plugin handle, which a bare plugin lacks.
        val plugin = spyk(NotificationPlugin(activity))
        every { plugin.getPermissionStates() } returns mapOf(LOCAL_NOTIFICATIONS to PermissionState.GRANTED)
        val manager = mockk<TauriNotificationManager>()
        every { manager.areNotificationsEnabled() } returns true
        NotificationPlugin::class.java.getDeclaredField("manager").apply {
            isAccessible = true
            set(plugin, manager)
        }
        val invoke = mockk<Invoke>(relaxed = true)

        plugin.requestPermissions(invoke)

        verify { invoke.resolve(match<JSObject> { it.getString("permissionState") == "granted" }) }
    }
}
