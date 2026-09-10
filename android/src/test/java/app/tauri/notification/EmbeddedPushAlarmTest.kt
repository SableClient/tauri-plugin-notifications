package app.tauri.notification

import android.app.AlarmManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EmbeddedPushAlarmTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val alarms
        get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Test
    fun schedulesAWakeupAlarmThatSurvivesDoze() {
        EmbeddedPushAlarm.schedule(context, 60_000L)

        val alarm = alarms.nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)
    }

    @Test
    fun cancelDropsThePendingAlarm() {
        EmbeddedPushAlarm.schedule(context, 60_000L)
        EmbeddedPushAlarm.cancel(context)

        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun rescheduleReplacesTheAlarmInsteadOfStacking() {
        EmbeddedPushAlarm.schedule(context, 60_000L)
        EmbeddedPushAlarm.schedule(context, 120_000L)

        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun anAlarmForAnotherDistributorDoesNotStartTheService() {
        val state = UnifiedPushStateStore(context)
        state.activeProvider = "unifiedpush"
        state.endpoint = ENDPOINT

        EmbeddedPushAlarm.onAlarm(context)

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun anAlarmWithoutAnEndpointDoesNotStartTheService() {
        UnifiedPushStateStore(context).activeProvider = "embedded"

        EmbeddedPushAlarm.onAlarm(context)

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun anAlarmForTheEmbeddedDistributorRestartsTheService() {
        val state = UnifiedPushStateStore(context)
        state.activeProvider = "embedded"
        state.endpoint = ENDPOINT

        EmbeddedPushAlarm.onAlarm(context)

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(EmbeddedPushService::class.java.name, started.component?.className)
    }

    private companion object {
        const val ENDPOINT = "https://ntfy.sh/up0123456789ab?up=1"
    }
}
