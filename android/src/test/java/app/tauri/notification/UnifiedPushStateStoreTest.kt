package app.tauri.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UnifiedPushStateStoreTest {

    private fun newStore() = UnifiedPushStateStore(RuntimeEnvironment.getApplication())

    @Test
    fun persistsRegistrationAcrossInstances() {
        val store = newStore()
        store.activeProvider = "unifiedpush"
        store.endpoint = "https://ntfy.sh/upAbc?up=1"
        store.p256dh = "pubkey"
        store.auth = "authsecret"
        store.distributor = "io.heckel.ntfy"
        store.vapid = "vapidkey"

        val reopened = newStore()
        assertEquals("unifiedpush", reopened.activeProvider)
        assertEquals("https://ntfy.sh/upAbc?up=1", reopened.endpoint)
        assertEquals("pubkey", reopened.p256dh)
        assertEquals("authsecret", reopened.auth)
        assertEquals("io.heckel.ntfy", reopened.distributor)
        assertEquals("vapidkey", reopened.vapid)
    }

    @Test
    fun clearRegistrationDropsKeysButKeepsProvider() {
        val store = newStore()
        store.activeProvider = "unifiedpush"
        store.endpoint = "https://ntfy.sh/upAbc?up=1"
        store.p256dh = "pubkey"
        store.auth = "authsecret"
        store.distributor = "io.heckel.ntfy"
        store.vapid = "vapidkey"

        store.clearRegistration()

        assertNull(store.endpoint)
        assertNull(store.p256dh)
        assertNull(store.auth)
        assertNull(store.distributor)
        assertNull(store.vapid)
        assertEquals("unifiedpush", store.activeProvider)
    }

    @Test
    fun activeProviderIsNullWhenUnset() {
        assertNull(newStore().activeProvider)
    }

    @Test
    fun `remembers the built-in distributor choice separately from having none`() {
        val store = newStore()

        assertFalse(store.useEmbeddedDistributor)

        store.useEmbeddedDistributor = true
        assertTrue(newStore().useEmbeddedDistributor)

        store.useEmbeddedDistributor = false
        assertFalse(newStore().useEmbeddedDistributor)
    }

    @Test
    fun `clearing a registration keeps the built-in distributor choice`() {
        val store = newStore()
        store.useEmbeddedDistributor = true
        store.endpoint = "https://ntfy.sh/upabc"

        store.clearRegistration()

        // Re-registering must not silently fall back to a Google-backed distributor.
        assertTrue(newStore().useEmbeddedDistributor)
    }
}
