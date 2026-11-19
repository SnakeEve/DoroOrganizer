package com.doro.organizer.debug

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The debug-only `:crashtest` process of L-15 (testing.md § 5, technique "Process death"): runs one DbMaintenance
 * operation, or `completeStartup`, without `SessionGate`, with a fault hook that kills the process at the requested
 * step. `DoroApplication` starts nothing in this process, so the container built here is the only one. The test binds,
 * waits for the binder to die and then starts the app in its own process to check the crash-safety matrix row.
 * Reaching the end of an operation means the step was never hit; the process then stays alive and the test fails on
 * its timeout. A request whose fault the vault injects has no step to die at: a failed read-back
 * (`CommitOutcome.Unknown`) lets its operation end on its own, and the process is killed then; a kill after the slot
 * commit dies inside the vault.
 */
class CrashTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.production().io)

    override fun onBind(intent: Intent): IBinder {
        val request = CrashRequest.from(intent)
        // The system restarts a bound service whose process died; the operation must run exactly once per test.
        val ran = File(noBackupFilesDir, CRASH_RUN_MARKER).apply { parentFile?.mkdirs() }
        if (!ran.createNewFile()) return Binder()
        scope.launch {
            val hook = if (request.faultInVault) MaintenanceFaultHook.None else KillingFaultHook(request.op.hookOp, request.step)
            val container = CrashTestContainer(application, hook, request.readBackFails, request.killAfterSlotCommit)
            runCrashRequest(applicationContext, container, request, scope)
            if (request.readBackFails) Process.killProcess(Process.myPid())
        }
        return Binder()
    }
}
