package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.security.SlotCipher
import kotlinx.coroutines.CompletableDeferred

/**
 * The questions a restore asks through `status` and the answers the UI gives (db-lifecycle.md § Interface and status,
 * § Cancellation policy). One restore at a time owns it, between [begin] and [end]. An answer that matches no pending
 * question is discarded (a password is zeroed); [cancel] works only until the user confirmed. A cancel before [begin]
 * is dropped: the checking dialog, whose Cancel button calls it, shows only once the restore published its first
 * `Running(Restore, PRECHECK)`, which follows [begin].
 */
internal class RestoreQuestions {
    enum class Kind { Password, Biometric, Confirmation }

    sealed interface Answer {
        val kind: Kind

        /** Null cancels the restore. Owned by the restore from the answer on. */
        class Password(val chars: CharArray?) : Answer {
            override val kind get() = Kind.Password

            override fun toString() = "Password"
        }

        /** Null: use the password instead. */
        class Biometric(val cipher: SlotCipher?) : Answer {
            override val kind get() = Kind.Biometric

            override fun toString() = "Biometric"
        }

        class Confirmation(val confirmed: Boolean) : Answer {
            override val kind get() = Kind.Confirmation

            override fun toString() = "Confirmation(confirmed=$confirmed)"
        }
    }

    private class Question(val kind: Kind, val answer: CompletableDeferred<Answer?>)

    private val lock = Any()
    private var pending: Question? = null
    private var cancellable = false
    private val cancelActions = mutableListOf<() -> Unit>()

    /** Set by [cancel]; checked before every step and between the blocks of `COPY_INPUT`. */
    @Volatile
    var cancelled = false
        private set

    fun begin() = synchronized(lock) {
        cancelled = false
        cancellable = true
    }

    /**
     * Runs [action] when the user cancels: closing the input's descriptor, triggering the statements' cancellation
     * signal. Runs it at once when the restore is already cancelled.
     */
    fun onCancel(action: () -> Unit) {
        val now = synchronized(lock) {
            if (!cancelled) cancelActions += action
            cancelled
        }
        if (now) action()
    }

    fun end() {
        val question = synchronized(lock) {
            cancellable = false
            cancelActions.clear()
            pending.also { pending = null }
        }
        question?.answer?.complete(null)
    }

    /** `cancelRestore()`: ends an unconfirmed restore with `RestoreCancelled`, also while no question is pending. */
    fun cancel() {
        val question: Question?
        val actions: List<() -> Unit>
        synchronized(lock) {
            if (!cancellable) return
            cancelled = true
            cancellable = false
            question = pending.also { pending = null }
            actions = cancelActions.toList()
            cancelActions.clear()
        }
        question?.answer?.complete(null)
        actions.forEach { it() }
    }

    fun answer(answer: Answer) {
        val question = synchronized(lock) {
            pending?.takeIf { it.kind == answer.kind }?.also {
                pending = null
                // The confirmation ends cancellability in the same step: only the swap remains, nothing cancels it.
                if (answer is Answer.Confirmation && answer.confirmed) {
                    cancellable = false
                    cancelActions.clear()
                }
            }
        }
        if (question == null || !question.answer.complete(answer)) discard(answer)
    }

    /** `NeedsBackupPassword` or `NeedsDatabasePassword`; [show] publishes it. Throws [RestoreEnd] when cancelled. */
    suspend fun password(show: () -> Unit): CharArray =
        (ask(Kind.Password, show) as Answer.Password?)?.chars ?: throw RestoreEnd.cancelled()

    /** `NeedsBiometricUnlock`: the authorized cipher, or null for "use the password". */
    suspend fun biometric(show: () -> Unit): SlotCipher? {
        val answer = ask(Kind.Biometric, show) as Answer.Biometric? ?: throw RestoreEnd.cancelled()
        return answer.cipher
    }

    /** `NeedsConfirmation`; returns only when the user confirmed. */
    suspend fun confirmation(show: () -> Unit) {
        val answer = ask(Kind.Confirmation, show) as Answer.Confirmation?
        if (answer?.confirmed != true) throw RestoreEnd.cancelled()
    }

    fun throwIfCancelled() {
        if (cancelled) throw RestoreEnd.cancelled()
    }

    /** Every step before the confirmation first honors `cancelRestore()` (step 3), then runs as [OperationRun.step]. */
    suspend fun step(run: OperationRun, step: PipelineStep) {
        throwIfCancelled()
        run.step(step)
    }

    /** The question is registered before [show] publishes it, so an answer given at once is never lost. */
    private suspend fun ask(kind: Kind, show: () -> Unit): Answer? {
        val question = Question(kind, CompletableDeferred())
        synchronized(lock) {
            if (cancelled) return null
            pending = question
        }
        show()
        return question.answer.await()
    }

    private fun discard(answer: Answer) {
        if (answer is Answer.Password) answer.chars?.fill('\u0000')
    }

    override fun toString() = "RestoreQuestions"
}

/** Ends a restore before its confirmation with [result]; caught by `RestorePipeline`, never escapes it. */
internal class RestoreEnd(val result: AppResult<MaintenanceResult>) : Exception("restore ended") {
    companion object {
        fun cancelled() = RestoreEnd(AppResult.Ok(MaintenanceResult.RestoreCancelled))
    }
}
