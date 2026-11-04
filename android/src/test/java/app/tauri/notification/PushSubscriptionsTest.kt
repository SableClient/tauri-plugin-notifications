package app.tauri.notification

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushSubscriptionsTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun accountsDevicesAndSenderKeysHaveIndependentPersistentSubscriptions() {
        val subscriptions = PushSubscriptions(context)
        val alice = subscriptions.instance("@alice:one.test", "A", "sender-one")
        val bob = subscriptions.instance("@bob:two.test", "B", "sender-two")
        val rotated = subscriptions.instance("@alice:one.test", "A", "sender-two")
        val otherDevice = subscriptions.instance("@alice:one.test", "B", "sender-one")
        assertEquals(4, setOf(alice, bob, rotated, otherDevice).size)
        subscriptions.remember(alice)
        subscriptions.remember(bob)
        val restarted = PushSubscriptions(context)
        assertEquals(alice, restarted.instance("@alice:one.test", "A", "sender-one="))
        assertEquals(bob, restarted.instance("@bob:two.test", "B", "sender-two"))
        assertEquals(setOf(alice, bob), restarted.instances())
        assertFalse(restarted.contains(rotated))
    }

    @Test
    fun recreatingARejectedSubscriptionPreservesOtherAccountsAndRejectsLateCallbacks() {
        val subscriptions = PushSubscriptions(context)
        val alice = subscriptions.instance("@alice:one.test", "A", "sender-one")
        val bob = subscriptions.instance("@bob:two.test", "B", "sender-two")
        subscriptions.remember(alice)
        subscriptions.remember(bob)
        subscriptions.forget(alice)
        val replacement = subscriptions.instance("@alice:one.test", "A", "sender-one")
        assertNotEquals(alice, replacement)
        assertFalse(subscriptions.contains(alice))
        assertTrue(subscriptions.contains(bob))
        assertEquals(bob, subscriptions.instance("@bob:two.test", "B", "sender-two"))
    }

    @Test
    fun inactiveAccountsStillAcceptDeliveryAfterAnAccountSwitchAndRestart() {
        val subscriptions = PushSubscriptions(context)
        val alice = subscriptions.instance("@alice:one.test", "A", "sender-one")
        val bob = subscriptions.instance("@bob:two.test", "B", "sender-two")
        subscriptions.remember(alice)
        subscriptions.remember(bob)
        val state = UnifiedPushStateStore(context)
        state.activeInstance = bob
        state.activeProvider = PushProvider.FCM
        state.distributor = context.packageName
        val restarted = UnifiedPushStateStore(context)
        assertTrue(restarted.acceptsUnifiedPush(alice))
        assertTrue(restarted.acceptsUnifiedPush(bob))
        subscriptions.forget(alice)
        assertFalse(restarted.acceptsUnifiedPush(alice))
        assertTrue(restarted.acceptsUnifiedPush(bob))
    }
}
