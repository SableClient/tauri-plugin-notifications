package app.tauri.notification

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle

internal class HostActivityCallbacks(
    private val hostClass: Class<out Activity>,
    private var current: Activity?,
    private val onWebViewGone: () -> Unit,
    private val onLaunch: (Intent) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity.javaClass != hostClass || activity === current) return
        current = activity
        if (savedInstanceState != null) return
        onWebViewGone()
        activity.intent?.let(onLaunch)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !== current || activity.isChangingConfigurations) return
        current = null
        onWebViewGone()
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
