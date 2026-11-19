package com.doro.organizer

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import com.doro.organizer.model.WindowPolicy

/** The window calls a [WindowPolicy] makes; production [ActivityWindow], tests record them. */
interface PolicyWindow {
    fun setSecure(secure: Boolean)

    /** API 33+ only. */
    fun setRecentsScreenshotEnabled(enabled: Boolean)

    /** API 31+ only. */
    fun setHideOverlayWindows(hide: Boolean)
}

/** Applies [policy] live, without recreation (security.md § 8); each call only on the API level that has it. */
fun applyWindowPolicy(policy: WindowPolicy, window: PolicyWindow, sdkInt: Int = Build.VERSION.SDK_INT) {
    window.setSecure(policy.secure)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) window.setRecentsScreenshotEnabled(policy.recentsScreenshot)
    if (sdkInt >= Build.VERSION_CODES.S) window.setHideOverlayWindows(policy.hideOverlays)
}

/** [PolicyWindow] of the single activity's window. */
class ActivityWindow(private val activity: Activity) : PolicyWindow {
    override fun setSecure(secure: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (secure) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }

    // The guards repeat applyWindowPolicy's, which takes the level as a parameter so tests can choose it.
    override fun setRecentsScreenshotEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(enabled)
    }

    override fun setHideOverlayWindows(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activity.window.setHideOverlayWindows(hide)
    }
}
