package com.doro.organizer.ui

import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.ListWindow
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.assertFocusedDialogWindow
import com.doro.organizer.testing.assertFocusedMenuWindow
import com.doro.organizer.testing.show
import com.doro.organizer.testing.showDialog
import com.doro.organizer.testing.visitListWindows
import com.doro.organizer.ui.common.EntityPicker
import com.doro.organizer.ui.common.EntityPickerUiState
import com.doro.organizer.ui.common.PickerActions
import com.doro.organizer.ui.common.RestoreBackupPasswordDialog
import com.doro.organizer.ui.common.RestoreConfirmDialog
import com.doro.organizer.ui.gate.EraseDialog
import com.doro.organizer.ui.gate.StartFreshDialog
import com.doro.organizer.ui.settings.BackupPasswordDialog
import com.doro.organizer.ui.settings.KeyChangeDialog
import com.doro.organizer.ui.settings.KeyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * testing.md L-25, input privacy (DEC-71, Q11: the keyboard must never learn; security.md § 11): every note, person,
 * project and search field asks for no personalized learning whatever the screenshot setting and the window policy,
 * and autofill stays out of the activity window in every gate state and out of every dialog window.
 */
@RunWith(Enclosed::class)
class InputPolicyTest {
    @RunWith(AndroidJUnit4::class)
    class Fields : ScriptedGateComposeTest() {
        @Test
        fun noLearningAlways_protectionOff_policyOpen() = noLearningAlways(protection = false, policyClosed = false)

        @Test
        fun noLearningAlways_protectionOn_policyOpen() = noLearningAlways(protection = true, policyClosed = false)

        @Test
        fun noLearningAlways_protectionOff_policyClosed() = noLearningAlways(protection = false, policyClosed = true)

        @Test
        fun noLearningAlways_protectionOn_policyClosed() = noLearningAlways(protection = true, policyClosed = true)

        /**
         * Starts in one combination, then visits every covered field: the perspective search, the person and the
         * project name (typed in the embedded pickers' search fields), the note content and the full-screen
         * `EntityPicker` search field; each is checked as composed, and again after screenshot protection was toggled
         * while it has focus.
         */
        private fun noLearningAlways(protection: Boolean, policyClosed: Boolean) {
            runBlocking { scene.settings.setScreenshotProtection(protection) }
            // An unknown key mode closes the window policy (security.md § 8), and the content stays composed.
            if (policyClosed) scene.maintenance.keyMode.value = null
            scene.launch(GateState.Ready)
            ui.awaitShell()
            awaitSecure(protection || policyClosed)

            compose.onNodeWithContentDescription(SEARCH).performClick()
            checkField(SEARCH_FIELD, protection, policyClosed)
            compose.onNodeWithTag(TAB_PEOPLE).performClick()
            checkField(PICKER_SEARCH, protection, policyClosed)
            compose.onNodeWithTag(TAB_PROJECTS).performClick()
            checkField(PICKER_SEARCH, protection, policyClosed)
            compose.onNodeWithTag(TAB_ALL).performClick()
            compose.onNodeWithTag(FAB).performClick()
            checkField(EDITOR_CONTENT, protection, policyClosed)
            compose.onNode(hasText(ADD_PERSON) and hasClickAction()).performClick()
            checkField(PICKER_SEARCH, protection, policyClosed)
        }

        private fun checkField(tag: String, protection: Boolean, policyClosed: Boolean) {
            compose.focus(tag)
            assertNoLearning("$tag (protection $protection, policy closed $policyClosed)")
            setProtection(!protection, policyClosed)
            assertNoLearning("$tag after protection turned ${!protection}")
            setProtection(protection, policyClosed)
        }

        /**
         * Toggles screenshot protection and waits until the settings hold it and the window shows its flag; with the
         * policy closed the flag stays set either way, so the settings value is what proves the toggle happened.
         */
        private fun setProtection(on: Boolean, policyClosed: Boolean) {
            runBlocking { scene.settings.setScreenshotProtection(on) }
            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.settings.settings.value?.screenshotProtection == on }
            compose.waitForIdle()
            awaitSecure(on || policyClosed)
        }

        @Test
        fun passwordFields_exposeThePasswordSemantics() {
            scene.launch(GateState.NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = false))
            ui.awaitPasswordScreen()
            compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assert(isPassword)

            showDialog { changePasswordDialog() }
            compose.onAllNodes(isPassword).assertCountEquals(3) // current, new and repeat
        }

        @Test
        fun autofillIsExcluded_onTheActivityWindow_inEveryGateState_withProtectionOffAndOn() {
            // MainActivity keeps its splash screen while the gate is Starting, so it launches in a later state and
            // the walk reaches Starting with the activity up.
            scene.launch(gateStates.first { it !is GateState.Starting })
            for (protection in listOf(false, true)) {
                runBlocking { scene.settings.setScreenshotProtection(protection) }
                for (state in gateStates) {
                    scene.gate.moveTo(state)
                    compose.waitForIdle()
                    scene.onActivity { activity ->
                        assertEquals(
                            "$state, protection $protection",
                            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                            activity.window.decorView.importantForAutofill,
                        )
                    }
                }
            }
        }

        /** L-25's dialog windows; each is a security dialog (security.md § 9.4), so it also hides overlays. */
        @Test
        fun everyDialogWindow_excludesAutofill_onItsOwnDecorView() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            val dialogs: List<Pair<String, @Composable () -> Unit>> = listOf(
                "key change" to { changePasswordDialog() },
                "backup password" to { BackupPasswordDialog(share = false, onConfirm = {}, onDismiss = {}) },
                "restore password" to { RestoreBackupPasswordDialog(retry = false, onAnswer = {}) },
                "restore confirmation" to {
                    val preview = RestorePreview(3, 1, 1, KeyMode.PLAIN, encryptionTurnsOff = false)
                    RestoreConfirmDialog(preview, onAnswer = {})
                },
                "Start fresh" to { StartFreshDialog(onDismiss = {}, onConfirm = {}) },
                "Erase" to { EraseDialog(onDismiss = {}, onConfirm = {}) },
            )
            for ((name, dialog) in dialogs) {
                showDialog(dialog)
                assertFocusedDialogWindow(hidesOverlays = true, name)
            }
        }

        @Composable
        private fun changePasswordDialog() = KeyChangeDialog(
            flow = KeyFlow.ChangePassword,
            mode = KeyMode.PASSWORD,
            lockOn = false,
            busy = false,
            error = null,
            retryAt = 0L,
            appTime = scene.time,
            onConfirm = { _, _ -> },
            onDismiss = {},
        )
    }

    /**
     * The list's and the editor's dialog, sheet and menu windows (ui.md § 3–§ 6) keep autofill out of their own
     * windows; none is a security dialog, so none hides overlays (security.md § 9.4).
     */
    @RunWith(AndroidJUnit4::class)
    class ListAndEditorWindows : ListComposeTest() {
        @Test
        fun everyDialogSheetAndMenuWindow_excludesAutofill() = visitListWindows { name, window ->
            when (window) {
                ListWindow.Screen -> Unit
                ListWindow.Dialog -> assertFocusedDialogWindow(hidesOverlays = false, name)
                ListWindow.Menu -> assertFocusedMenuWindow(name)
            }
        }
    }

    /** The flag depends on no `SettingsStore` read: these settings never emit, so the window policy stays closed. */
    @RunWith(AndroidJUnit4::class)
    class SettingsNeverRead : ScriptedGateComposeTest(settingsRead = false) {
        @Test
        fun noLearningAlways_onAFieldComposedInReady() {
            scene.launch(GateState.Ready)
            awaitSecure(true)
            scene.onActivity { it.show { PersonPicker() } }

            compose.focus(PICKER_SEARCH)

            assertNoLearning(PICKER_SEARCH)
            assertEquals(null, scene.settings.settings.value)
        }

        /** The real picker over a list still loading: its search field is where person names are typed. */
        @Composable
        private fun PersonPicker() {
            var search by remember { mutableStateOf("") }
            EntityPicker(
                kind = EntityKind.Person,
                purpose = null,
                search = search,
                state = EntityPickerUiState(
                    loading = true,
                    anyEntity = false,
                    items = emptyList(),
                    tab = EntityKind.Person,
                    chosen = emptyMap(),
                    canConfirm = false,
                ),
                actions = PickerActions(onSearchChange = { search = it }, onPick = {}),
                searchFocus = remember { FocusRequester() },
                listState = rememberLazyListState(),
            )
        }
    }

    private companion object {
        const val SEARCH_FIELD = "search_field"
        const val PICKER_SEARCH = "picker_search"
        const val EDITOR_CONTENT = "editor_content"
        const val FAB = "fab_create"
        const val TAB_ALL = "tab_All"
        const val TAB_PEOPLE = "tab_Individual"
        const val TAB_PROJECTS = "tab_Project"

        // Literals copied from ui.md § 11.
        const val SEARCH = "Search"
        const val ADD_PERSON = "Add person"

        val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

        val gateStates = listOf(
            GateState.Starting(authenticated = false),
            GateState.NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = false),
            GateState.Locked(Resume.Ready),
            GateState.Opening(viaPassword = false, lockPending = false, transientError = false),
            GateState.Ready,
            GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready),
            GateState.Corrupt,
            GateState.KeyUnavailable,
            GateState.Maintenance(MaintenanceOp.Restore, Origin.Corrupt),
        )

        /** Taps the field tagged [tag] and waits until it has focus. */
        fun ComposeTestRule.focus(tag: String) {
            waitUntil(GateUi.TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(tag).performClick()
            waitUntil(GateUi.TIMEOUT_MS) { onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}

/** The activity window's `FLAG_SECURE` reaches [secure]: the window policy has been applied (security.md § 8). */
private fun ScriptedGateComposeTest.awaitSecure(secure: Boolean) = compose.waitUntil(GateUi.TIMEOUT_MS) {
    var flags = 0
    scene.onActivity { flags = it.window.attributes.flags }
    (flags and WindowManager.LayoutParams.FLAG_SECURE != 0) == secure
}

/**
 * The focused field's input session sets `IME_FLAG_NO_PERSONALIZED_LEARNING` (testing.md L-25): read from the
 * `EditorInfo` that `onCreateInputConnection` fills on the `AndroidComposeView` that has focus, as a keyboard gets it.
 */
private fun ScriptedGateComposeTest.assertNoLearning(field: String) {
    var options: Int? = null
    compose.waitUntil(GateUi.TIMEOUT_MS) {
        scene.onActivity { activity ->
            val info = EditorInfo()
            if (activity.window.decorView.findFocus()?.onCreateInputConnection(info) != null) options = info.imeOptions
        }
        options != null
    }
    assertTrue("$field learns", checkNotNull(options) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
}
