package app.tauri.notification

import kotlin.test.assertNotNull
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class EmbeddedPushServiceLegacyApiTest {
    private lateinit var service: EmbeddedPushService

    @Before
    fun setup() {
        PushDelegates.override(RecordingPushDelegate())
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        service = Robolectric.buildService(EmbeddedPushService::class.java).create().get()
        UnifiedPushStateStore(service).apply {
            markEmbedded()
            endpoint = "https://ntfy.sh/up0123456789ab?up=1"
        }
    }

    @After
    fun teardown() {
        service.onDestroy()
        PushDelegates.override(null)
    }

    @Test
    fun postsTheForegroundNotificationWithoutNotificationChannels() {
        service.onStartCommand(null, 0, 1)

        assertNotNull(shadowOf(service).lastForegroundNotification)
    }
}
