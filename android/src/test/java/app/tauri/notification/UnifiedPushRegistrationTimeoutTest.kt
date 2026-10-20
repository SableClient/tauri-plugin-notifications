package app.tauri.notification

import android.app.Activity
import android.content.ComponentName
import android.content.IntentFilter
import android.os.Looper
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.time.Duration
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.UnifiedPush

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedPushRegistrationTimeoutTest {
    private lateinit var plugin: NotificationPlugin
    private lateinit var state: UnifiedPushStateStore
    private lateinit var invoke: Invoke

    @Before
    fun setup() {
        assumeTrue(BuildConfig.ENABLE_PUSH_NOTIFICATIONS)
        mockkObject(CachedKeyManager.Companion)
        every { CachedKeyManager.getInstance(any()) } returns mockk(relaxed = true) {
            every { getPublicKeySet(any()) } returns null
        }
        mockkStatic(UnifiedPush::class)
        every { UnifiedPush.register(any(), any(), any(), any(), any()) } just Runs
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val component = ComponentName("io.heckel.ntfy", "PushReceiver")
        shadowOf(activity.packageManager).apply {
            addReceiverIfNotPresent(component).exported = true
            addIntentFilterForReceiver(
                component, IntentFilter("org.unifiedpush.android.distributor.REGISTER"),
            )
        }
        UnifiedPush.saveDistributor(activity, "io.heckel.ntfy")
        state = UnifiedPushStateStore(activity)
        state.useEmbeddedDistributor = false
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
            provider = "unifiedpush"
            vapid = "vapid"
        }
    }

    @After
    fun teardown() {
        if (::plugin.isInitialized) plugin.onWebViewGone()
        unmockkStatic(UnifiedPush::class)
        unmockkObject(CachedKeyManager.Companion)
    }

    @Test
    fun aRenewalThatTimesOutKeepsTheWorkingRegistration() {
        state.activeProvider = "unifiedpush"
        state.activeInstance = UnifiedPushStateStore.INSTANCE
        state.endpoint = "https://ntfy.sh/upworking?up=1"
        state.p256dh = "p256dh"
        state.auth = "auth"

        plugin.registerForPushNotifications(invoke)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        verify(exactly = 1) { invoke.reject("Timed out registering for push notifications") }
        assertEquals("unifiedpush", state.activeProvider)
        assertEquals("https://ntfy.sh/upworking?up=1", state.endpoint)
        assertEquals("p256dh", state.p256dh)
        assertEquals("auth", state.auth)
    }

    @Test
    fun aFirstRegistrationThatTimesOutStillTakesAnEndpointThatArrivesLate() {
        plugin.registerForPushNotifications(invoke)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        verify(exactly = 1) { invoke.reject("Timed out registering for push notifications") }
        assertEquals(null, state.endpoint)

        plugin.onUnifiedPushNewEndpoint("https://ntfy.sh/uplate?up=1", "p256dh", "auth", UnifiedPushStateStore.INSTANCE)

        assertEquals("https://ntfy.sh/uplate?up=1", state.endpoint)
        assertEquals("unifiedpush", state.activeProvider)
        verify { plugin.trigger("push-token", match<JSObject> { it.getBoolean("rotated") == true }) }
    }
}
