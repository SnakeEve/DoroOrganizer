package com.doro.organizer

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import com.doro.organizer.core.log.AppLog

/**
 * Debug: StrictMode (architecture.md, Threading). The main thread never touches disk, so every violation is logged,
 * by its class name only: `penaltyLog()` would print the offending intent, SAF URIs included (DEC-70).
 */
internal object DebugTools {
    fun install(app: Application) {
        val executor = app.mainExecutor
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyListener(executor, ::log)
                .build(),
        )
        val vm = StrictMode.VmPolicy.Builder().detectLeakedClosableObjects()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vm.detectUnsafeIntentLaunch()
        StrictMode.setVmPolicy(vm.penaltyListener(executor, ::log).build())
    }

    /**
     * The font scale an instrumented test gives `MainActivity` (testing.md I-35); null keeps the phone's. It goes into
     * the activity's configuration, which dialog and sheet windows inherit: a Compose `DeviceConfigurationOverride`
     * does not reach them, because each of their Compose views takes its density from its own context.
     */
    @Volatile
    var fontScale: Float? = null

    /** From `MainActivity.attachBaseContext`, before the activity's resources exist. */
    fun configure(activity: Activity) {
        val scale = fontScale ?: return
        activity.applyOverrideConfiguration(Configuration().also { it.fontScale = scale })
    }

    private fun log(violation: Violation) = AppLog.d("strict mode: ${violation.javaClass.simpleName}")
}
