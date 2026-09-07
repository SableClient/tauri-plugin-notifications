package app.tauri.notification

import android.app.Activity
import android.os.Looper
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.verify
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddedPushRegistrationTest {
    private lateinit var plugin: NotificationPlugin
    private lateinit var state: UnifiedPushStateStore
    private lateinit var invoke: Invoke

    @Before
    fun setup() {
        assumeTrue(BuildConfig.ENABLE_PUSH_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        state = UnifiedPushStateStore(activity)
        state.useEmbeddedDistributor = true
        plugin = spyk(NotificationPlugin(activity))
        every { plugin.trigger(any(), any<JSObject>()) } just Runs
        val manager = mockk<TauriNotificationManager>()
        every { manager.areNotificationsEnabled() } returns true
        NotificationPlugin::class.java.getDeclaredField("manager").apply {
            isAccessible = true
            set(plugin, manager)
        }
        invoke = mockk(relaxed = true)
        every { invoke.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            provider = "embedded"
            embeddedGatewayUrl = "https://ntfy.sh"
        }
    }

    @After
    fun teardown() {
        if (::plugin.isInitialized) plugin.onDestroy()
    }

    @Test
    fun migratesLegacyTopicAndWaitsForReadyBeforeReturningEndpoint() {
        val legacyTopic = "up0123456789abcdef01234567"
        state.embeddedTopic = legacyTopic
        plugin.registerForPushNotifications(invoke)

        val endpoint = state.endpoint!!
        assertNotEquals(legacyTopic, state.embeddedTopic)
        assertEquals(14, state.embeddedTopic!!.length)
        assertTrue(endpoint.endsWith("?up=1"))
        verify(exactly = 0) { invoke.resolve(any<JSObject>()) }
        verify(exactly = 0) { plugin.trigger(any(), any<JSObject>()) }

        plugin.onEmbeddedPushReady(endpoint)

        verify(exactly = 1) {
            invoke.resolve(match<JSObject> {
                it.getString("deviceToken") == endpoint &&
                    it.getString("p256dh").isNotEmpty() && it.getString("auth").isNotEmpty()
            })
        }
    }

    @Test
    fun ignoresReadinessForAnOldEndpoint() {
        plugin.registerForPushNotifications(invoke)
        plugin.onEmbeddedPushReady("https://ntfy.sh/up0123456789ab?up=1")

        verify(exactly = 0) { invoke.resolve(any<JSObject>()) }
    }

    @Test
    fun rejectsRegistrationIfSubscriberNeverBecomesReady() {
        plugin.registerForPushNotifications(invoke)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        plugin.onEmbeddedPushReady(state.endpoint!!)

        verify(exactly = 1) { invoke.reject("Timed out registering for push notifications") }
        verify(exactly = 0) { invoke.resolve(any<JSObject>()) }
    }

    @Test
    fun reusesCompatibleTopic() {
        state.embeddedTopic = "up0123456789ab"
        plugin.registerForPushNotifications(invoke)
        plugin.onEmbeddedPushReady(state.endpoint!!)
        plugin.onEmbeddedPushReady(state.endpoint!!)

        assertEquals("up0123456789ab", state.embeddedTopic)
        verify(exactly = 1) { invoke.resolve(any<JSObject>()) }
    }
}
