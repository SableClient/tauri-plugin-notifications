package app.tauri.notification

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerSettingsTest {
    private lateinit var activity: Activity
    private lateinit var plugin: NotificationPlugin

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        plugin = NotificationPlugin(activity)
        shadowOf(activity.application).checkActivities(true)
    }

    private fun handle(action: String) {
        val component = ComponentName("com.android.settings", action)
        shadowOf(activity.packageManager).apply {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                if (action != Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) addDataScheme("package")
            })
        }
    }

    @Test
    fun reportsWhetherExactAlarmsAreAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val invoke = mockk<Invoke>(relaxed = true)

        plugin.canScheduleExactAlarms(invoke)

        verify { invoke.resolve(match<JSObject> { !it.getBoolean("allowed") }) }
    }

    @Test
    fun opensTheExactAlarmScreenForThisApp() {
        handle(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        val invoke = mockk<Invoke>(relaxed = true)

        plugin.requestScheduleExactAlarms(invoke)

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, opened.action)
        assertEquals("package:${activity.packageName}", opened.dataString)
        verify { invoke.resolve() }
    }

    @Test
    fun fallsBackToTheBatteryListWhenTheDirectPromptIsMissing() {
        handle(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val invoke = mockk<Invoke>(relaxed = true)

        plugin.requestIgnoreBatteryOptimizations(invoke)

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        verify { invoke.resolve() }
    }

    @Test
    fun rejectsWhenNoBatteryScreenExists() {
        val invoke = mockk<Invoke>(relaxed = true)

        plugin.requestIgnoreBatteryOptimizations(invoke)

        verify { invoke.reject("Could not open the battery optimization settings") }
    }
}
