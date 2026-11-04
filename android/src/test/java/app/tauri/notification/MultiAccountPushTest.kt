package app.tauri.notification

import android.app.Activity
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
class MultiAccountPushTest {
    private lateinit var plugin: NotificationPlugin
    private lateinit var requestedInstance: String

    @Before
    fun setup() {
        assumeTrue(BuildConfig.ENABLE_PUSH_NOTIFICATIONS && BuildConfig.FLAVOR == "gms")
        mockkObject(FcmBridge, CachedKeyManager.Companion)
        mockkStatic(UnifiedPush::class)
        every { FcmBridge.isAvailable(any()) } returns true
        every { CachedKeyManager.getInstance(any()) } returns mockk(relaxed = true)
        every { UnifiedPush.register(any(), any(), any(), any(), any()) } answers {
            requestedInstance = secondArg()
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        plugin = spyk(NotificationPlugin(activity))
        every { plugin.trigger(any(), any<JSObject>()) } just Runs
        val manager = mockk<TauriNotificationManager>()
        every { manager.areNotificationsEnabled() } returns true
        NotificationPlugin::class.java.getDeclaredField("manager").apply {
            isAccessible = true
            set(plugin, manager)
        }
        NotificationPlugin::class.java.getDeclaredField("hasPushMessageListener").apply {
            isAccessible = true
            setBoolean(plugin, true)
        }
    }

    @After
    fun teardown() {
        if (::plugin.isInitialized) plugin.onWebViewGone()
        unmockkStatic(UnifiedPush::class)
        unmockkObject(FcmBridge, CachedKeyManager.Companion)
    }

    private fun register(user: String, vapid: String): String {
        val request = mockk<Invoke>(relaxed = true)
        every { request.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            provider = "fcm"
            userId = user
            deviceId = "DEVICE"
            this.vapid = vapid
        }
        plugin.registerForPushNotifications(request)
        val instance = requestedInstance
        plugin.onUnifiedPushNewEndpoint("https://fcm.googleapis.com/fcm/send/$instance", "p256dh", "auth", instance)
        verify { request.resolve(match<JSObject> { it.getString("instance") == instance }) }
        return instance
    }

    @Test
    fun changingSenderKeyCreatesANewSubscription() {
        val original = register("@alice:one.test", "sender-one")
        val replacement = register("@alice:one.test", "sender-two")
        assertNotEquals(original, replacement)
        assertEquals(replacement, register("@alice:one.test", "sender-two"))
    }

    @Test
    fun disablingPushRetiresEveryAccount() {
        val alice = register("@alice:one.test", "sender-one")
        val bob = register("@bob:two.test", "sender-two")
        every { UnifiedPush.unregister(any(), any(), any()) } just Runs
        NotificationPlugin::class.java.getDeclaredField("notificationManager").apply {
            isAccessible = true
            set(plugin, mockk<android.app.NotificationManager>(relaxed = true))
        }
        val disable = mockk<Invoke>(relaxed = true)
        plugin.unregisterForPushNotifications(disable)
        verify { disable.resolve() }
        verify { UnifiedPush.unregister(any(), alice, any()) }
        verify { UnifiedPush.unregister(any(), bob, any()) }
        assertTrue(!plugin.onUnifiedPushMessage("alice-message", alice))
        assertTrue(!plugin.onUnifiedPushMessage("bob-message", bob))
    }

    @Test
    fun retiringAPendingSubscriptionRejectsItsLateEndpoint() {
        val request = mockk<Invoke>(relaxed = true)
        every { request.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            provider = "fcm"
            userId = "@alice:one.test"
            deviceId = "DEVICE"
            vapid = "sender-one"
        }
        plugin.registerForPushNotifications(request)
        val pending = requestedInstance
        every { UnifiedPush.unregister(any(), any(), any()) } just Runs
        val logout = mockk<Invoke>(relaxed = true)
        every { logout.parseArgs(PushInstanceArgs::class.java) } returns PushInstanceArgs().apply { instance = pending }
        plugin.unregisterPushInstance(logout)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        plugin.onUnifiedPushNewEndpoint("https://fcm.googleapis.com/fcm/send/$pending", "p256dh", "auth", pending)
        verify { request.reject("Push registration cancelled by unregister") }
        verify(exactly = 0) { request.resolve(any<JSObject>()) }
        assertTrue(!plugin.onUnifiedPushMessage("late-message", pending))
    }

    @Test
    fun retiringOneAccountDoesNotCancelAnotherAccountsRegistration() {
        val alice = register("@alice:one.test", "sender-one")
        val bobRequest = mockk<Invoke>(relaxed = true)
        every { bobRequest.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            provider = "fcm"
            userId = "@bob:two.test"
            deviceId = "DEVICE"
            vapid = "sender-two"
        }
        plugin.registerForPushNotifications(bobRequest)
        val bob = requestedInstance
        every { UnifiedPush.unregister(any(), any(), any()) } just Runs
        val logout = mockk<Invoke>(relaxed = true)
        every { logout.parseArgs(PushInstanceArgs::class.java) } returns PushInstanceArgs().apply { instance = alice }
        plugin.unregisterPushInstance(logout)
        plugin.onUnifiedPushNewEndpoint("https://fcm.googleapis.com/fcm/send/$bob", "p256dh", "auth", bob)
        verify { bobRequest.resolve(match<JSObject> { it.getString("instance") == bob }) }
        verify(exactly = 0) { bobRequest.reject(any<String>()) }
    }

    @Test
    fun accountsOnDifferentServersRetainIndependentDelivery() {
        val alice = register("@alice:one.test", "sender-one")
        val bob = register("@bob:two.test", "sender-two")
        assertNotEquals(alice, bob)
        assertTrue(plugin.onUnifiedPushMessage("alice-message", alice))
        assertTrue(plugin.onUnifiedPushMessage("bob-message", bob))
        assertEquals(alice, register("@alice:one.test", "sender-one"))
        assertTrue(plugin.onUnifiedPushMessage("bob-message-while-alice-active", bob))
    }
}
