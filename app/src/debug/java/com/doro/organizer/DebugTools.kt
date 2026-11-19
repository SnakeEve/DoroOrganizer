package com.doro.organizer

import android.app.Application
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

    private fun log(violation: Violation) = AppLog.d("strict mode: ${violation.javaClass.simpleName}")
}
