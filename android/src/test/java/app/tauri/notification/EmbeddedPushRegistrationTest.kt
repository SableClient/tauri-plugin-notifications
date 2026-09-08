package app.tauri.notification

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.verify
import java.time.Duration
import kotlin.test.assertFalse
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
        mockkObject(CachedKeyManager.Companion)
        every { CachedKeyManager.getInstance(any()) } returns mockk(relaxed = true) {
            every { getPublicKeySet(any()) } returns null
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        state = UnifiedPushStateStore(activity)
        state.useEmbeddedDistributor = true
        PushDiagnostics.drain(activity)
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
        unmockkObject(CachedKeyManager.Companion)
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
        val endpoint = state.endpoint!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        plugin.onEmbeddedPushReady(endpoint)

        assertEquals(1, PushDiagnostics.drain(org.robolectric.RuntimeEnvironment.getApplication()).counts["EMBEDDED_REGISTRATION_TIMEOUT"])
        verify(exactly = 1) { invoke.reject("Timed out registering for push notifications") }
        verify(exactly = 0) { invoke.resolve(any<JSObject>()) }
        assertEquals(null, state.activeProvider)
        assertEquals(null, state.distributor)
        assertEquals(null, state.endpoint)
    }

    @Test
    fun explicitEmbeddedProviderIgnoresSavedExternalDistributor() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        val component = ComponentName("io.heckel.ntfy", "PushReceiver")
        val packages = shadowOf(application.packageManager)
        packages.addReceiverIfNotPresent(component).exported = true
        packages.addIntentFilterForReceiver(
            component, IntentFilter("org.unifiedpush.android.distributor.REGISTER"),
        )
        org.unifiedpush.android.connector.UnifiedPush.saveDistributor(application, "io.heckel.ntfy")
        state.useEmbeddedDistributor = false

        plugin.registerForPushNotifications(invoke)

        assertEquals("embedded", state.activeProvider)
        assertEquals(NotificationPlugin.EMBEDDED_DISTRIBUTOR, state.distributor)
        assertTrue(state.endpoint!!.endsWith("?up=1"))
    }

    @Test
    fun unregisteringEmbeddedClearsBootEligibleRegistration() {
        plugin.registerForPushNotifications(invoke)
        val provisionalEndpoint = state.endpoint!!
        val unregister = mockk<Invoke>(relaxed = true)

        plugin.unregisterForPushNotifications(unregister)

        assertEquals(null, state.activeProvider)
        assertEquals(null, state.distributor)
        assertEquals(null, state.endpoint)
        assertTrue(state.useEmbeddedDistributor)

        plugin.onEmbeddedPushReady(provisionalEndpoint)
        verify(exactly = 0) { invoke.resolve(any<JSObject>()) }

        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService
        EmbeddedPushBootReceiver().onReceive(
            org.robolectric.RuntimeEnvironment.getApplication(),
            Intent(Intent.ACTION_BOOT_COMPLETED),
        )
        assertEquals(null, shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun timeoutRestoresAnExistingExternalRegistration() {
        state.activeProvider = "unifiedpush"
        state.endpoint = "https://ntfy.sh/upprevious?up=1"
        state.distributor = "io.heckel.ntfy"
        state.useEmbeddedDistributor = false

        plugin.registerForPushNotifications(invoke)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        assertEquals("unifiedpush", state.activeProvider)
        assertEquals("https://ntfy.sh/upprevious?up=1", state.endpoint)
        assertEquals("io.heckel.ntfy", state.distributor)
    }

    @Test
    fun unregisteringPendingEmbeddedReregistrationClearsTheOldRegistration() {
        plugin.registerForPushNotifications(invoke)
        plugin.onEmbeddedPushReady(state.endpoint!!)
        plugin.registerForPushNotifications(invoke)

        plugin.unregisterForPushNotifications(mockk(relaxed = true))

        assertEquals(null, state.activeProvider)
        assertEquals(null, state.endpoint)
        assertEquals(null, state.distributor)
    }

    @Test
    fun timeoutRestoresEmbeddedReplayAndRestartsThePreviousSocket() {
        plugin.registerForPushNotifications(invoke)
        val endpoint = state.endpoint!!
        plugin.onEmbeddedPushReady(endpoint)
        state.initializeEmbeddedReplay(endpoint, 100)
        state.completeEmbeddedPush(endpoint, "already-delivered")
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService
        val replacement = mockk<Invoke>(relaxed = true)
        every { replacement.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            provider = "embedded"
            embeddedGatewayUrl = "https://ntfy.sh"
        }

        plugin.registerForPushNotifications(replacement)
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        assertEquals("embedded", state.activeProvider)
        assertFalse(state.shouldProcessEmbeddedPush(endpoint, "already-delivered", 200))
        assertEquals(
            EmbeddedPushService::class.java.name,
            shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService.component!!.className,
        )
    }

    @Test
    fun switchesFromBuiltInToExternalDistributor() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        val component = ComponentName("io.heckel.ntfy", "PushReceiver")
        val packages = shadowOf(application.packageManager)
        packages.addReceiverIfNotPresent(component).exported = true
        packages.addIntentFilterForReceiver(
            component, IntentFilter("org.unifiedpush.android.distributor.REGISTER"),
        )
        assertEquals(listOf("io.heckel.ntfy"), org.unifiedpush.android.connector.UnifiedPush.getDistributors(application))
        plugin.registerForPushNotifications(invoke)
        plugin.onEmbeddedPushReady(state.endpoint!!)
        val selection = mockk<Invoke>(relaxed = true)
        every { selection.parseArgs(DistributorArgs::class.java) } returns DistributorArgs().apply {
            distributor = "io.heckel.ntfy"
        }
        plugin.setDistributor(selection)
        assertFalse(state.useEmbeddedDistributor)
        val externalRegistration = mockk<Invoke>(relaxed = true)
        every { externalRegistration.parseArgs(RegisterPushArgs::class.java) } returns RegisterPushArgs().apply {
            provider = "auto"
            embeddedGatewayUrl = "https://ntfy.sh"
        }
        plugin.registerForPushNotifications(externalRegistration)
        verify(exactly = 0) { externalRegistration.reject(any<String>()) }
        val externalEndpoint = "https://ntfy.sh/up0123456789ab?up=1"
        plugin.onUnifiedPushNewEndpoint(externalEndpoint, "external-key", "external-auth", UnifiedPushStateStore.INSTANCE)

        assertEquals("unifiedpush", state.activeProvider)
        assertEquals("io.heckel.ntfy", state.distributor)
        assertEquals(externalEndpoint, state.endpoint)
        verify { externalRegistration.resolve(match<JSObject> { it.optString("deviceToken") == externalEndpoint }) }
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
