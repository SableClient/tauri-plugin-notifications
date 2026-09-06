package app.tauri.notification

import android.app.Activity
import app.tauri.plugin.JSObject
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddedPushMessageTest {
    private lateinit var plugin: NotificationPlugin
    private lateinit var state: UnifiedPushStateStore
    private val message = """{"app_id":"app","ack_token":"activation-token"}"""

    @Before
    fun setup() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        state = UnifiedPushStateStore(activity)
        state.activeProvider = "embedded"
        state.activeInstance = UnifiedPushStateStore.INSTANCE
        plugin = spyk(NotificationPlugin(activity))
        setListenerActive(true)
        every { plugin.trigger(any(), any<JSObject>()) } just Runs
    }

    private fun setListenerActive(active: Boolean) {
        NotificationPlugin::class.java.getDeclaredField("hasPushMessageListener").apply {
            isAccessible = true
            setBoolean(plugin, active)
        }
    }

    @Test
    fun forwardsActivationMessageFromEmbeddedDistributor() {
        plugin.onUnifiedPushMessage(message, UnifiedPushStateStore.INSTANCE)

        verify(exactly = 1) {
            plugin.trigger("push-message", match<JSObject> { it.getString("message") == message })
        }
    }

    @Test
    fun forwardsMessageFromExternalDistributor() {
        state.activeProvider = "unifiedpush"
        plugin.onUnifiedPushMessage(message, UnifiedPushStateStore.INSTANCE)

        verify(exactly = 1) { plugin.trigger("push-message", any<JSObject>()) }
    }

    @Test
    fun rejectsMessagesFromStaleInstances() {
        plugin.onUnifiedPushMessage(message, "stale-instance")

        verify(exactly = 0) { plugin.trigger(any(), any<JSObject>()) }
    }

    @Test
    fun rejectsMessagesAfterSwitchingToFcm() {
        state.activeProvider = "fcm"
        plugin.onUnifiedPushMessage(message, UnifiedPushStateStore.INSTANCE)

        verify(exactly = 0) { plugin.trigger(any(), any<JSObject>()) }
    }

    @Test
    fun doesNotDispatchWithoutAListener() {
        setListenerActive(false)
        plugin.onUnifiedPushMessage(message, UnifiedPushStateStore.INSTANCE)

        verify(exactly = 0) { plugin.trigger(any(), any<JSObject>()) }
    }
}
