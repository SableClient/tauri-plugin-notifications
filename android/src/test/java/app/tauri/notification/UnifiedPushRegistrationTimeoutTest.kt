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

        plugin.onUnifiedPushNewEndpoint("https://ntfy.sh/uplate?up=1", "p256dh", "auth", state.instanceForRegistration("vapid"))

        assertEquals("https://ntfy.sh/uplate?up=1", state.endpoint)
        assertEquals("unifiedpush", state.activeProvider)
        verify { plugin.trigger("push-token", match<JSObject> { it.getBoolean("rotated") == true }) }
    }

    @Test
    fun theTimeoutWaitsForThePermissionPrompt() {
        val manager = mockk<TauriNotificationManager>()
        every { manager.areNotificationsEnabled() } returns false
        NotificationPlugin::class.java.getDeclaredField("manager").apply {
            isAccessible = true
            set(plugin, manager)
        }
        every { plugin["requestPermissionForAlias"](any<String>(), any<Invoke>(), any<String>()) } returns Unit

        plugin.registerForPushNotifications(invoke)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        verify(exactly = 0) { invoke.reject(any<String>()) }

        every { manager.areNotificationsEnabled() } returns true
        NotificationPlugin::class.java.getDeclaredMethod("pushPermissionsCallback", Invoke::class.java).apply {
            isAccessible = true
            invoke(plugin, invoke)
        }
        verify(exactly = 1) { UnifiedPush.register(any(), any(), any(), any(), any()) }
    }
    @Test
    fun switchingAccountsKeepsBothSubscriptionsAndLogoutRetiresOnlyOne() {
        every { UnifiedPush.unregister(any(), any(), any()) } just Runs
        fun register(user: String, key: String): String {
            val request = mockk<Invoke>(relaxed = true)
            every { request.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
                provider = "unifiedpush"
                vapid = key
                userId = user
                deviceId = "DEVICE"
            }
            plugin.registerForPushNotifications(request)
            val instance = state.instanceForRegistration(key)
            plugin.onUnifiedPushNewEndpoint("https://push.test/$instance", "key-$user", "auth", instance)
            verify { request.resolve(any<JSObject>()) }
            return instance
        }
        val alice = register("@alice:one.test", "sender-one")
        val bob = register("@bob:two.test", "sender-two")
        kotlin.test.assertNotEquals(alice, bob)
        kotlin.test.assertTrue(state.acceptsUnifiedPush(alice))
        kotlin.test.assertTrue(state.acceptsUnifiedPush(bob))
        NotificationPlugin::class.java.getDeclaredField("hasPushMessageListener").apply {
            isAccessible = true
            setBoolean(plugin, true)
        }
        kotlin.test.assertTrue(plugin.onUnifiedPushMessage("alice-message", alice))
        kotlin.test.assertTrue(plugin.onUnifiedPushMessage("bob-message", bob))
        verify { plugin.trigger("push-message", match<JSObject> { it.getString("instance") == alice }) }
        val logout = mockk<Invoke>(relaxed = true)
        every { logout.parseArgs(PushInstanceArgs::class.java) } returns PushInstanceArgs().apply { instance = alice }
        plugin.unregisterPushInstance(logout)
        kotlin.test.assertFalse(state.acceptsUnifiedPush(alice))
        kotlin.test.assertTrue(state.acceptsUnifiedPush(bob))
        kotlin.test.assertTrue(plugin.onUnifiedPushMessage("bob-still-receives", bob))
        verify(exactly = 1) { UnifiedPush.unregister(any(), alice, any()) }
        verify(exactly = 0) { UnifiedPush.unregister(any(), bob, any()) }
    }

}
