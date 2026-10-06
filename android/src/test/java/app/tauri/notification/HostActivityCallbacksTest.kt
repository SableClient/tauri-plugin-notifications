package app.tauri.notification

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostActivityCallbacksTest {
    class HostActivity : Activity() {
        var changing = false
        override fun isChangingConfigurations() = changing
    }

    class OtherActivity : Activity()

    private val launched = mutableListOf<Intent>()
    private var webViewsGone = 0

    private fun host(intent: Intent = Intent()) = HostActivity().apply { setIntent(intent) }

    private fun callbacks(first: Activity) =
        HostActivityCallbacks(HostActivity::class.java, first, { webViewsGone++ }, { launched.add(it) })

    @Test
    fun aHostRecreatedAfterTheTaskWasRemovedDeliversItsTap() {
        val first = host()
        val callbacks = callbacks(first)
        val tap = Intent(Intent.ACTION_MAIN)

        callbacks.onActivityDestroyed(first)
        callbacks.onActivityCreated(host(tap), null)

        assertEquals(listOf(tap), launched)
        assertEquals(2, webViewsGone)
    }

    @Test
    fun aConfigurationChangeKeepsTheListenersAndDoesNotReplayTheLaunch() {
        val first = host().apply { changing = true }
        val callbacks = callbacks(first)

        callbacks.onActivityDestroyed(first)
        callbacks.onActivityCreated(host(), Bundle())

        assertTrue(launched.isEmpty())
        assertEquals(0, webViewsGone)
    }

    @Test
    fun aRestoredHostLeavesItsOriginalIntentAlone() {
        val first = host()
        val callbacks = callbacks(first)

        callbacks.onActivityDestroyed(first)
        callbacks.onActivityCreated(host(), Bundle())

        assertTrue(launched.isEmpty())
        assertEquals(1, webViewsGone)
    }

    @Test
    fun theOldHostFinishingLateDoesNotSilenceTheNewOne() {
        val first = host()
        val callbacks = callbacks(first)
        val second = host()

        callbacks.onActivityCreated(second, null)
        callbacks.onActivityDestroyed(first)

        assertEquals(1, webViewsGone)
        callbacks.onActivityDestroyed(second)
        assertEquals(2, webViewsGone)
    }

    @Test
    fun otherActivitiesAreIgnored() {
        val first = host()
        val callbacks = callbacks(first)
        val other = OtherActivity().apply { intent = Intent(Intent.ACTION_VIEW) }

        callbacks.onActivityCreated(other, null)
        callbacks.onActivityDestroyed(other)
        callbacks.onActivityCreated(first, null)

        assertTrue(launched.isEmpty())
        assertEquals(0, webViewsGone)
    }
}
