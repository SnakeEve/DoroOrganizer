package com.doro.organizer.data.maintenance

/**
 * The per-operation state of the operation frame (db-lifecycle.md § Operation frame): which step runs, whether
 * `MaintenanceStarted` was raised and the commit started, the outcome so far and the secrets the frame zeroes at its
 * end. Confined to the one coroutine that runs the operation.
 */
internal class OperationRun(
    val op: MaintenanceOp,
    private val hook: MaintenanceFaultHook,
    private val publish: (MaintenanceStatus) -> Unit,
    private val raiseStarted: suspend (fingerprintReset: Boolean) -> Unit,
) {
    var started = false
        private set

    /** Set immediately before the first `commitGeneration`: from then on an escaping failure is `Unknown`. */
    var commitStarted = false

    /** The `MaintenanceFinished` outcome; reported if and only if [started]. */
    var outcome: MaintenanceOutcome? = null

    /** `Finished.previousContentLost` of a backup to a document. */
    var previousContentLost = false

    private val secrets = mutableListOf<ByteArray>()

    /** Publishes `Running(op, step)`, then runs the fault hook immediately before the step. */
    suspend fun step(step: PipelineStep) {
        publish(MaintenanceStatus.Running(op, step))
        hook.before(op, step)
    }

    /** Publishes a restore question (`Needs…`) or the step an answer resumed; no hook runs. */
    fun show(status: MaintenanceStatus) = publish(status)

    /** Publishes `Running(op, step, fraction)` while a copy of known length runs; no hook runs. */
    fun progress(step: PipelineStep, fraction: Float) = publish(MaintenanceStatus.Running(op, step, fraction))

    /** Frame step 6: SessionGate enters `Maintenance(op, origin)` before the next step runs. */
    suspend fun start(fingerprintReset: Boolean = false) {
        raiseStarted(fingerprintReset)
        started = true
    }

    /** Registers [secret] for zeroing at the end of the frame; returns it. */
    fun own(secret: ByteArray): ByteArray = secret.also { secrets += it }

    /** Ownership of [secret] passed on (to the holder or the reset hand-over): the frame must not zero it. */
    fun handOver(secret: ByteArray) {
        secrets.removeAll { it === secret }
    }

    /** The frame's last act on secrets: every connection keyed with them is closed by then. */
    fun zeroSecrets() {
        secrets.forEach { it.fill(0) }
        secrets.clear()
    }

    override fun toString() = "OperationRun(op=$op)"
}
