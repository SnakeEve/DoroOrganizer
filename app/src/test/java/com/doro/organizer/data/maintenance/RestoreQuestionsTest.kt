package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.AppResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question and answer protocol of a restore (db-lifecycle.md § Interface and status, § Cancellation policy): an
 * answer reaches only the question of its kind, never-delivered passwords are zeroed, and cancelling works until the
 * confirmation.
 */
class RestoreQuestionsTest {
    private val questions = RestoreQuestions().apply { begin() }

    @Test
    fun anAnswerGivenWhileTheQuestionIsPublished_isNotLost() = runTest {
        val password = "backup password".toCharArray()

        val chars = questions.password { questions.answer(RestoreQuestions.Answer.Password(password)) }

        assertSame(password, chars)
    }

    @Test
    fun anAnswerOfAnotherKind_isDiscarded_andAPasswordInItIsZeroed() = runTest {
        val wrongKind = "typed into a stale dialog".toCharArray()
        val confirmed = async(start = CoroutineStart.UNDISPATCHED) {
            questions.confirmation { questions.answer(RestoreQuestions.Answer.Password(wrongKind)) }
        }

        assertTrue(wrongKind.all { it == '\u0000' })
        assertFalse(confirmed.isCompleted)
        questions.answer(RestoreQuestions.Answer.Confirmation(true))
        confirmed.await()
    }

    @Test
    fun aPasswordWithoutAPendingQuestion_isZeroed() {
        val late = "late".toCharArray()

        questions.answer(RestoreQuestions.Answer.Password(late))

        assertArrayEquals(CharArray(4), late)
    }

    @Test
    fun aNullPasswordAndADeclinedConfirmation_cancelTheRestore() = runTest {
        val noPassword = runCatching { questions.password { questions.answer(RestoreQuestions.Answer.Password(null)) } }
        val declined = runCatching { questions.confirmation { questions.answer(RestoreQuestions.Answer.Confirmation(false)) } }

        assertCancelled(noPassword.exceptionOrNull())
        assertCancelled(declined.exceptionOrNull())
    }

    @Test
    fun aNullBiometricAnswer_meansUseThePassword_notCancel() = runTest {
        val cipher = questions.biometric { questions.answer(RestoreQuestions.Answer.Biometric(null)) }

        assertNull(cipher)
    }

    @Test
    fun cancel_endsThePendingQuestion_runsTheCancelActions_andEveryLaterQuestion() = runTest {
        var closed = 0
        questions.onCancel { closed++ }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { questions.biometric {} } }

        questions.cancel()

        assertCancelled(pending.await().exceptionOrNull())
        assertEquals(1, closed)
        assertTrue(questions.cancelled)
        assertCancelled(runCatching { questions.throwIfCancelled() }.exceptionOrNull())
        assertCancelled(runCatching { questions.confirmation {} }.exceptionOrNull())
        questions.onCancel { closed++ } // registered after the cancel: runs at once
        assertEquals(2, closed)
    }

    @Test
    fun aCancelRightAfterTheConfirmingAnswer_changesNothing() = runTest {
        var closed = false
        questions.onCancel { closed = true }
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { questions.confirmation {} } }

        questions.answer(RestoreQuestions.Answer.Confirmation(true))
        questions.cancel() // before the restore resumed from its confirmation

        assertTrue(confirmation.await().isSuccess)
        assertFalse(questions.cancelled)
        assertFalse(closed)
        questions.throwIfCancelled()
    }

    @Test
    fun aDecliningAnswer_leavesTheRestoreCancellable() = runTest {
        var closed = 0
        questions.onCancel { closed++ }
        val declined = runCatching { questions.confirmation { questions.answer(RestoreQuestions.Answer.Confirmation(false)) } }

        questions.cancel()

        assertCancelled(declined.exceptionOrNull())
        assertTrue(questions.cancelled)
        assertEquals(1, closed)
    }

    @Test
    fun aNewRestore_startsUncancelled() {
        questions.cancel()
        questions.end()

        questions.begin()

        assertFalse(questions.cancelled)
    }

    private fun assertCancelled(failure: Throwable?) {
        assertEquals(AppResult.Ok(MaintenanceResult.RestoreCancelled), (failure as RestoreEnd).result)
    }
}
