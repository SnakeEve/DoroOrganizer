package com.doro.organizer.ui.gate

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.captureSavedState
import com.doro.organizer.testing.composeSavedState
import com.doro.organizer.testing.holdsText
import com.doro.organizer.testing.marshalled
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The password screen (ui.md § 10.2) on the real gate in `PASSWORD` mode with the lock off, so a cold start asks the
 * password: L-02 (the throttled screen) and the UI part of L-03 (the password leaves the field as an array and never
 * reaches the saved-instance Bundle).
 */
@RunWith(AndroidJUnit4::class)
class PasswordScreenTest : GateComposeTest() {
    private suspend fun coldStartOnThePasswordScreen() {
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
    }

    @Test
    fun throttled_disablesFieldAndUnlock_countsDown_andKeepsForgotEnabled() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        scene.settings.setBackoff(failures = 5, retryAtElapsedMs = scene.time.elapsedMillis() + 30_000)
        coldStartOnThePasswordScreen()

        compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assertIsNotEnabled()
        compose.onNodeWithTag(GateUi.UNLOCK).assertIsNotEnabled()
        compose.onNodeWithTag(FORGOT).assertIsEnabled()
        compose.onNodeWithText(throttled("0:30"), useUnmergedTree = true).assertExists()

        // The countdown is retryAt minus the AppTime reading, recomputed every second.
        scene.time.advanceBy(Duration.ofSeconds(5))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(throttled("0:25"), useUnmergedTree = true).assertExists()

        scene.time.advanceBy(Duration.ofSeconds(25))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithTextCount(WRONG_PASSWORD) == 1
        }
        compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assertIsEnabled()
        compose.onNodeWithText(throttled("0:00"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun submit_handsThePasswordToTheGate_emptiesTheField_andShowsAWrongPassword() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        coldStartOnThePasswordScreen()

        ui.enterPassword("wrong password")

        scene.awaitGate { it is GateState.NeedsPassword && it.failures == 1 }
        compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodesWithTextCount(WRONG_PASSWORD) == 1 }
        compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assert(hasEditableText(""))
        assertEquals(1, scene.settings.snapshot().unlockFailures)

        ui.enterPassword(PASSWORD)

        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
    }

    @Test
    fun savedInstanceState_neverHoldsThePassword() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        coldStartOnThePasswordScreen()
        compose.onNodeWithTag(GateUi.PASSWORD_FIELD).performTextInput(SENTINEL)
        compose.onNodeWithContentDescription(SHOW_PASSWORD).performClick() // a saveable toggle next to the text
        compose.onNodeWithContentDescription(HIDE_PASSWORD).assertExists()

        val saved = captureSavedState { scene.moveActivityTo(Lifecycle.State.CREATED) }

        val composeState = checkNotNull(saved.composeSavedState()) { "Compose saved no state" }
        assertFalse("the screen's saveable state was written", composeState.isEmpty)
        assertFalse("the sentinel in the Bundle (UTF-16 or UTF-8)", saved.marshalled().holdsText(SENTINEL))
    }

    private fun hasEditableText(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private fun ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun throttled(countdown: String) = "Too many attempts. Try again in $countdown."

    private companion object {
        const val PASSWORD = "correct horse"
        const val FORGOT = "gate_forgot"

        /** `password_show` and `password_hide`, ui.md § 11. */
        const val SHOW_PASSWORD = "Show password"
        const val HIDE_PASSWORD = "Hide password"

        /** `password_wrong`, ui.md § 11.11. */
        const val WRONG_PASSWORD = "Wrong password."
    }
}
