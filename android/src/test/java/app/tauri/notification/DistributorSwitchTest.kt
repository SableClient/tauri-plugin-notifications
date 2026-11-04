package app.tauri.notification

import android.app.Activity
import android.content.ComponentName
import android.content.IntentFilter
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
class DistributorSwitchTest {
    private lateinit var plugin: NotificationPlugin
    private lateinit var state: UnifiedPushStateStore

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
            addIntentFilterForReceiver(component, IntentFilter("org.unifiedpush.android.distributor.REGISTER"))
        }
        state = UnifiedPushStateStore(activity)
        state.markEmbedded()
        state.useEmbeddedDistributor = true
        state.endpoint = "https://ntfy.sh/upembedded?up=1"
        plugin = spyk(NotificationPlugin(activity))
        every { plugin.trigger(any(), any<JSObject>()) } just Runs
        val manager = mockk<TauriNotificationManager>()
        every { manager.areNotificationsEnabled() } returns true
        NotificationPlugin::class.java.getDeclaredField("manager").apply {
            isAccessible = true
            set(plugin, manager)
        }
    }

    @After
    fun teardown() {
        if (::plugin.isInitialized) plugin.onWebViewGone()
        unmockkStatic(UnifiedPush::class)
        unmockkObject(CachedKeyManager.Companion)
    }

    private fun register(provider: String, vapid: String?): Invoke = mockk<Invoke>(relaxed = true).also {
        every { it.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            this.provider = provider
            this.vapid = vapid
        }
        plugin.registerForPushNotifications(it)
    }

    @Test
    fun switchingFromEmbeddedToAnInstalledDistributorTakesItsEndpoint() {
        val select = mockk<Invoke>(relaxed = true)
        every { select.parseArgs(DistributorArgs::class.java) } returns DistributorArgs().apply {
            distributor = "io.heckel.ntfy"
        }
        plugin.setDistributor(select)
        verify { select.resolve() }

        val first = register("unifiedpush", "vapid")
        plugin.onUnifiedPushNewEndpoint("https://ntfy.sh/upnew", "p256dh", "auth", state.instanceForRegistration("vapid"))
        verify { first.resolve(match<JSObject> { it.getString("deviceToken") == "https://ntfy.sh/upnew" }) }

        val again = register("unifiedpush", null)
        plugin.onUnifiedPushNewEndpoint("https://ntfy.sh/upnew", null, null, state.instanceForRegistration(null))
        verify { again.resolve(match<JSObject> { it.getString("deviceToken") == "https://ntfy.sh/upnew" }) }
    }
}
