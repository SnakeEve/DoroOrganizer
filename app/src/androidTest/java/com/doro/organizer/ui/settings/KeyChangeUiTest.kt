package com.doro.organizer.ui.settings

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The key-change dialogs of ui.md § 8.8 over a scripted gate and DbMaintenance: a wrong current password takes the
 * focus back to its field, also after a rotation; a key change that reached the maintenance overlay does not come
 * back when the gate is `Ready` again before `changeKey` has returned. The flows themselves are
 * `SettingsViewModelTest`, the texts and outcomes `MaintenanceHostViewTest`.
 */
@RunWith(AndroidJUnit4::class)
class KeyChangeUiTest : ScriptedGateComposeTest() {
    @Test
    fun aWrongCurrentPassword_takesTheFocusBack_alsoAfterARotation() {
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.maintenance.keyChangeResult = AppResult.Err(AppError.WrongPassword)
        openSettings()
        compose.onNodeWithTag(PASSWORD_CHANGE).performScrollTo().performClick()
        ui.awaitText(CHANGE_TITLE)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("not the password")
        fields[1].performTextInput(NEW_PASSWORD)
        fields[2].performTextInput(NEW_PASSWORD)
        compose.onNodeWithText(ACKNOWLEDGE).performClick()
        compose.onNodeWithText(CHANGE).performClick()

        ui.awaitText(WRONG_PASSWORD)
        compose.onAllNodes(hasSetTextAction())[0].assertIsFocused()
        scene.recreateActivity()
        ui.awaitText(WRONG_PASSWORD)
        compose.onAllNodes(hasSetTextAction())[0].assertIsFocused()
        assertEquals(listOf("changeKey:PASSWORD:true:true", "acknowledge"), scene.maintenance.calls)
    }

    @Test
    fun aKeyChangeThatReachedTheOverlay_staysHidden_untilChangeKeyReturns() {
        val held = CompletableDeferred<AppResult<MaintenanceResult>>()
        scene.maintenance.keyChangeHeld = held
        scene.maintenance.keyMode.value = KeyMode.DEVICE
        openSettings()
        compose.onNodeWithTag(PASSWORD_SET).performScrollTo().performClick()
        ui.awaitText(SET_TITLE)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(NEW_PASSWORD)
        fields[1].performTextInput(NEW_PASSWORD)
        compose.onNodeWithText(ACKNOWLEDGE).performClick()
        compose.onNodeWithText(SET_PASSWORD).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.isNotEmpty() }

        scene.gate.moveTo(GateState.Maintenance(MaintenanceOp.KeyChange, Origin.Ready))
        compose.waitUntil(GateUi.TIMEOUT_MS) { ui.countText(SET_TITLE) == 0 }
        // Committed and reopened: the gate is Ready in PASSWORD mode while changeKey has not returned yet.
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.gate.moveTo(GateState.Ready)
        compose.waitForIdle()
        compose.onNodeWithText(SET_TITLE).assertDoesNotExist()
        compose.onNodeWithText(CURRENT_PASSWORD).assertDoesNotExist()

        held.complete(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.DEVICE, KeyMode.PASSWORD)))
        compose.waitForIdle()
        compose.onNodeWithText(SET_TITLE).assertDoesNotExist()
        assertEquals(listOf("changeKey:PASSWORD:false:true"), scene.maintenance.calls)
    }

    private fun openSettings() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag(SETTINGS_ACTION).performClick()
        ui.awaitTag(SETTINGS_LIST)
        ui.awaitTag(SCREENSHOT) // the security rows wait for the settings and the key mode
    }

    private companion object {
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"
        const val SCREENSHOT = "settings_screenshot"
        const val PASSWORD_SET = "settings_password_set"
        const val PASSWORD_CHANGE = "settings_password_change"
        const val NEW_PASSWORD = "a long new startup passphrase"

        // Literals copied from ui.md § 11.
        const val SET_TITLE = "Set a startup password"
        const val SET_PASSWORD = "Set password"
        const val CHANGE_TITLE = "Change the startup password"
        const val CHANGE = "Change password"
        const val CURRENT_PASSWORD = "Current password"
        const val ACKNOWLEDGE = "I understand this cannot be recovered"
        const val WRONG_PASSWORD = "Wrong password."
    }
}
