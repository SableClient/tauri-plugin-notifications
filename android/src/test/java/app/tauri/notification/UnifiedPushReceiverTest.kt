package app.tauri.notification

import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.data.PushMessage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedPushReceiverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val delegate = RecordingPushDelegate()
    private lateinit var state: UnifiedPushStateStore

    @Before
    fun setup() {
        PushDelegates.override(delegate)
        state = UnifiedPushStateStore(context)
    }

    @After
    fun teardown() {
        PushDelegates.override(null)
        NotificationPlugin.instance = null
    }

    private fun receiver() = Robolectric.buildService(UnifiedPushReceiver::class.java).create().get()

    private fun registerUnifiedPush(p256dh: String?, vapid: String? = "vapid") {
        state.activeProvider = PushProvider.UNIFIED_PUSH
        state.activeInstance = UnifiedPushStateStore.INSTANCE
        state.p256dh = p256dh
        state.vapid = vapid
    }

    private fun message(body: String, decrypted: Boolean) = PushMessage(body.toByteArray(), decrypted)

    @Test
    fun undecryptedMessagesAreDroppedOnceKeysArePublished() {
        registerUnifiedPush("p256dh")

        receiver().onMessage(message("forged", decrypted = false), UnifiedPushStateStore.INSTANCE)

        assertTrue(delegate.scheduled.isEmpty())
    }

    @Test
    fun decryptedMessagesAreScheduled() {
        registerUnifiedPush("p256dh")

        receiver().onMessage(message("real", decrypted = true), UnifiedPushStateStore.INSTANCE)

        assertEquals(listOf("real"), delegate.scheduled)
    }

    @Test
    fun plainMessagesAreAcceptedWhenNoKeysWerePublished() {
        registerUnifiedPush(null)

        receiver().onMessage(message("plain", decrypted = false), UnifiedPushStateStore.INSTANCE)

        assertEquals(listOf("plain"), delegate.scheduled)
    }

    @Test
    fun plainMessagesAreAcceptedForADirectRegistrationWithConnectorKeys() {
        registerUnifiedPush("p256dh", vapid = null)

        receiver().onMessage(message("plain", decrypted = false), UnifiedPushStateStore.INSTANCE)

        assertEquals(listOf("plain"), delegate.scheduled)
    }

    @Test
    fun anotherAccountsDirectRegistrationAcceptsPlainMessagesWhileTheActiveOneIsKeyed() {
        registerUnifiedPush("p256dh")
        val subscriptions = PushSubscriptions(context)
        val direct = subscriptions.instance("@b:example.org", "DEVICEB", null)
        subscriptions.remember(direct)

        receiver().onMessage(message("plain", decrypted = false), direct)

        assertEquals(listOf("plain"), delegate.scheduled)
    }

    @Test
    fun anotherAccountsKeyedRegistrationDropsPlainMessagesWhileTheActiveOneIsDirect() {
        registerUnifiedPush("p256dh", vapid = null)
        val subscriptions = PushSubscriptions(context)
        val keyed = subscriptions.instance("@b:example.org", "DEVICEB", "vapid")
        subscriptions.remember(keyed)

        receiver().onMessage(message("forged", decrypted = false), keyed)

        assertTrue(delegate.scheduled.isEmpty())
    }

    @Test
    fun unregisterClearsTheActiveRegistrationAndNotifiesTheWebView() {
        registerUnifiedPush("p256dh")
        state.endpoint = "https://ntfy.sh/up0123456789ab?up=1"
        val plugin = mockk<NotificationPlugin>(relaxed = true)
        NotificationPlugin.instance = plugin

        receiver().onUnregistered(UnifiedPushStateStore.INSTANCE)

        val reopened = UnifiedPushStateStore(context)
        assertNull(reopened.endpoint)
        assertNull(reopened.activeProvider)
        verify { plugin.onUnifiedPushUnregistered(UnifiedPushStateStore.INSTANCE) }
    }
}
