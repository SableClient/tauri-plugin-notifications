package app.tauri.notification

import android.app.AlarmManager
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddedPushRestartTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun markEmbeddedWithEndpoint() {
        UnifiedPushStateStore(context).apply {
            markEmbedded()
            endpoint = ENDPOINT
        }
    }

    private fun watchdogStates(): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("embedded-push-watchdog").get().map { it.state }

    @Test
    fun anAppUpdateRestartsTheService() {
        markEmbeddedWithEndpoint()

        EmbeddedPushBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(EmbeddedPushService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
    }

    @Test
    fun grantingExactAlarmsRestartsTheService() {
        markEmbeddedWithEndpoint()

        EmbeddedPushBootReceiver().onReceive(
            context,
            Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
        )

        assertNotNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        markEmbeddedWithEndpoint()

        EmbeddedPushBootReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun aRunningServiceKeepsAWatchdogScheduled() {
        markEmbeddedWithEndpoint()
        val service = Robolectric.buildService(EmbeddedPushService::class.java).create().get()

        service.syncConnection(replay = false)

        assertEquals(listOf(WorkInfo.State.ENQUEUED), watchdogStates())
        service.onDestroy()
    }

    @Test
    fun theWatchdogRestartsTheServiceWhileEmbeddedIsActive() {
        markEmbeddedWithEndpoint()

        val worker = TestListenableWorkerBuilder<EmbeddedPushWatchdog>(context).build()
        worker.doWork()

        assertNotNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun theWatchdogRetiresItselfOnceEmbeddedIsOff() {
        EmbeddedPushWatchdog.ensure(context)

        TestListenableWorkerBuilder<EmbeddedPushWatchdog>(context).build().doWork()

        assertTrue(watchdogStates().all { it == WorkInfo.State.CANCELLED })
        assertNull(shadowOf(context).nextStartedService)
    }

    private companion object {
        const val ENDPOINT = "https://ntfy.sh/up0123456789ab?up=1"
    }
}
