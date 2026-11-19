package com.doro.organizer.debug

import android.os.Process
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.PipelineStep

/**
 * Kills the process at the hook of ([op], [step]) (testing.md § 5, L-15): every earlier step has completed and this
 * one has not started. A thrown exception would not do, because `finally` blocks would run and hide leftovers.
 */
class KillingFaultHook(private val op: MaintenanceOp?, private val step: PipelineStep) : MaintenanceFaultHook {
    override suspend fun before(op: MaintenanceOp?, step: PipelineStep) {
        if (op == this.op && step == this.step) Process.killProcess(Process.myPid())
    }

    override fun toString() = "KillingFaultHook(op=$op, step=$step)"
}
