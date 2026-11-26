package com.doro.organizer.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.security.FingerprintAvailability
import com.doro.organizer.security.SlotOffer
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.TAB_LABELS
import com.doro.organizer.testing.assertNotCutOff
import com.doro.organizer.testing.assertTouchTarget
import com.doro.organizer.testing.atFontScale
import com.doro.organizer.testing.dialogCatalog
import com.doro.organizer.testing.hideDialogKeyboard
import com.doro.organizer.testing.showDialog
import com.doro.organizer.testing.textLayout
import com.doro.organizer.testing.visitListWindows
import com.doro.organizer.testing.visitShellDialogs
import com.doro.organizer.ui.settings.KeyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * testing.md I-35 (DEC-54, DEC-55; ui.md § 12): every clickable node of the rows, the calendar, the editor, the gate
 * screens and every dialog, sheet and menu has a touch target of at least 48 × 48 dp; at 200 % font scale the spec
 * strings stay displayed, the create button and the checkboxes stay reachable, and the buttons of the fingerprint
 * set-up offer, the kekBroken notice and the recovery screens' "Restore from backup" stay whole, and so do People's
 * overflow menu and its rename dialog (DEC-113); a row is one merged node and its checkbox a separate node with
 * `Role.Checkbox`. The font scale is set in the activity's configuration (`atFontScale`), which also reaches the
 * dialog windows.
 */
@RunWith(Enclosed::class)
class AccessibilityTest {
    @RunWith(AndroidJUnit4::class)
    class Lists : ListComposeTest() {
        @Test
        fun rowsCalendarEditorAndTheirDialogsSheetsAndMenus_everyClickable_hasA48dpTouchTarget() =
            visitListWindows { name, _ -> ui.assertEveryClickableHasATouchTarget(name) }

        @Test
        fun aRow_isOneMergedNode_andItsCheckbox_aSeparateCheckboxNode() {
            var id = 0L
            startWith { id = note(content = "Weekly sync\nBudget", createdAt = now) }
            awaitRow(id)

            val row = compose.onNodeWithTag(rowTag(id)).fetchSemanticsNode()
            val checkbox = compose.onNodeWithTag(checkboxTag(id)).fetchSemanticsNode()

            // The note has no link, so its description ends with "Orphaned" (ui.md § 12.1, § 3.7; DEC-27).
            val description = row.config.getOrNull(SemanticsProperties.ContentDescription)
            assertEquals(listOf("Weekly sync, Budget, $ORPHANED"), description)
            assertNull("the row reads its description, not its texts", row.config.getOrNull(SemanticsProperties.Text))
            val rowToggle = row.config.getOrNull(SemanticsProperties.ToggleableState)
            assertNull("the checkbox is not merged into the row", rowToggle)
            assertNotEquals(row.id, checkbox.id)
            assertEquals(Role.Checkbox, checkbox.config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.Off, checkbox.config.getOrNull(SemanticsProperties.ToggleableState))
        }

        @Test
        fun atTwiceTheFontScale_theSpecStringsStayDisplayed_andTheCreateButtonAndCheckboxesStayReachable() =
            atFontScale(2f) {
                val ids = mutableListOf<Long>()
                var overdueOrphan = 0L
                startWith {
                    overdueOrphan = note(content = "Call the bank", createdAt = now, date = today - 1)
                    ids += overdueOrphan
                    repeat(2) { ids += note(content = "Note $it", createdAt = now) }
                }
                ids.forEach(::awaitRow)

                for ((tag, label) in TAB_LABELS) compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasText(label))
                compose.onNodeWithTag(FAB).assertIsDisplayed().assertTouchTarget()
                for (id in ids) {
                    compose.onNodeWithTag(checkboxTag(id)).performScrollTo().assertIsDisplayed().assertTouchTarget()
                }
                // The row's own texts are cleared from its semantics (ui.md § 12.1); its description carries them.
                compose.onNodeWithTag(rowTag(overdueOrphan)).performScrollTo().assertIsDisplayed()
                val description = rowDescription(overdueOrphan)
                assertTrue(description, description.endsWith(", $OVERDUE, $ORPHANED"))

                compose.onNodeWithTag(DATE_CHIP).performClick()
                ui.awaitText(UNDATED)
                assertWhole(compose.onNodeWithText(UNDATED, useUnmergedTree = true).assertIsDisplayed().textLayout())
                ui.pressBack()

                compose.onNodeWithTag(rowTag(overdueOrphan)).performClick()
                ui.awaitTag(EditorUi.CONTENT)
                compose.onNodeWithContentDescription(ASSOCIATE).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag(EDITOR_DATE).performScrollTo().assertIsDisplayed().assertTouchTarget()
                compose.onNodeWithContentDescription(BACK).performClick()

                awaitRow(overdueOrphan)
                compose.onNodeWithTag(SETTINGS_ACTION).performClick()
                ui.awaitTag(SETTINGS_LIST)
                for (row in listOf(BACKUP_ALL_DATA, RESTORE_FROM_BACKUP)) {
                    val text = compose.onNode(hasText(row), useUnmergedTree = true).performScrollTo()
                    assertWhole(text.assertIsDisplayed().textLayout())
                }
                compose.onNodeWithContentDescription(BACK).performClick()

                awaitRow(overdueOrphan)
                compose.onNodeWithContentDescription(SEARCH).performClick()
                compose.onNodeWithTag(SEARCH_FIELD).performTextInput("no such text")
                ui.awaitText(NO_DATA)
                assertWhole(compose.onNodeWithText(NO_DATA, useUnmergedTree = true).assertIsDisplayed().textLayout())
            }

        /**
         * People at twice the font scale (DEC-113; ui.md § 2.2, § 8.17): Search, Settings and "More options" stay
         * displayed with 48 dp touch targets, and so does the title button; the menu's two items stay whole; the rename
         * dialog's title, field and buttons, measured with its keyboard hidden, stay whole and reachable.
         */
        @Test
        fun atTwiceTheFontScale_theEntityMenuAndTheRenameDialog_stayWholeAndReachable() = atFontScale(2f) {
            var alice = 0L
            startWith(settings = { setRemembered(EntityKind.Person, alice) }) { alice = person("Alice", now) }
            compose.onNodeWithTag(TAB_PEOPLE).performClick()
            ui.awaitTag(ENTITY_MENU)

            compose.onNodeWithContentDescription(SEARCH).assertIsDisplayed().assertTouchTarget()
            compose.onNodeWithTag(SETTINGS_ACTION).assertIsDisplayed().assertTouchTarget()
            compose.onNodeWithTag(ENTITY_MENU).assertIsDisplayed().assertTouchTarget()
                .assert(hasContentDescription(MORE_OPTIONS))
            compose.onNode(titleButton("Alice")).assertIsDisplayed().assertTouchTarget()

            compose.onNodeWithTag(ENTITY_MENU).performClick()
            ui.awaitText(RENAME_ITEM)
            for (item in listOf(RENAME_ITEM, DELETE_ITEM)) {
                compose.onNode(hasText(item) and hasClickAction()).assertIsDisplayed().assertTouchTarget()
                assertWhole(compose.onNodeWithText(item, useUnmergedTree = true).textLayout())
            }
            compose.onNodeWithText(RENAME_ITEM).performClick()
            ui.awaitText(RENAME_PERSON)
            compose.hideDialogKeyboard()

            assertWhole(compose.onNodeWithText(RENAME_PERSON, useUnmergedTree = true).assertIsDisplayed().textLayout())
            val field = compose.onNodeWithTag(RENAME_FIELD).performScrollTo().assertIsDisplayed().assertTouchTarget()
            // As laid out, not clipped: a field cut off by the dialog does not pass.
            val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val node = field.fetchSemanticsNode()
            val fieldBounds = Rect(node.positionInRoot, node.size.toSize())
            val inside = fieldBounds.left >= dialog.left && fieldBounds.top >= dialog.top &&
                fieldBounds.right <= dialog.right && fieldBounds.bottom <= dialog.bottom
            assertTrue("the field $fieldBounds lies inside the dialog $dialog", inside)
            val label = hasText(NAME) and hasAnyAncestor(hasTestTag(RENAME_FIELD))
            assertWhole(compose.onNode(label, useUnmergedTree = true).assertIsDisplayed().textLayout())
            for (button in listOf(CANCEL, RENAME)) {
                compose.onNode(hasText(button) and hasClickAction()).assertIsDisplayed().assertTouchTarget()
                val text = hasText(button) and hasAnyAncestor(hasClickAction())
                assertWhole(compose.onNode(text, useUnmergedTree = true).textLayout())
            }
        }

        private companion object {
            const val FAB = "fab_create"
            const val SEARCH_FIELD = "search_field"
            const val DATE_CHIP = "chip_Date"
            const val EDITOR_DATE = "editor_date"
            const val SETTINGS_ACTION = "action_settings"
            const val SETTINGS_LIST = "settings_list"
            const val TAB_PEOPLE = "tab_Individual"

            // Literals copied from ui.md § 11.
            const val UNDATED = "Undated"
            const val ASSOCIATE = "Associate persons and projects"
            const val BACK = "Back"
            const val BACKUP_ALL_DATA = "Backup all data"
            const val RESTORE_FROM_BACKUP = "Restore from backup"
            const val RENAME_ITEM = "Rename…"
            const val DELETE_ITEM = "Delete…"
            const val RENAME_PERSON = "Rename person"
            const val NAME = "Name"
            const val RENAME = "Rename"
            const val CANCEL = "Cancel"
        }
    }

    @RunWith(AndroidJUnit4::class)
    class Gates : ScriptedGateComposeTest() {
        @Test
        fun gateScreens_everyClickable_hasA48dpTouchTarget() {
            scene.lock.fingerprintAvailable = FingerprintAvailability.Available
            scene.launch(GateState.NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = false))
            ui.awaitPasswordScreen()
            ui.assertEveryClickableHasATouchTarget("password screen")

            ui.click(GateUi.FORGOT) // the sweep may have left the screen scrolled
            ui.awaitForgotScreen()
            ui.assertEveryClickableHasATouchTarget("Forgot password screen")

            scene.gate.moveTo(GateState.Locked(Resume.Ready))
            ui.awaitLockScreen()
            ui.assertEveryClickableHasATouchTarget("lock screen")

            scene.gate.moveTo(GateState.Corrupt)
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            ui.assertEveryClickableHasATouchTarget("Corrupt screen")

            scene.gate.moveTo(GateState.KeyUnavailable)
            ui.awaitRecoveryScreen(GateUi.KEY_UNAVAILABLE_TITLE)
            ui.assertEveryClickableHasATouchTarget("KeyUnavailable screen")

            scene.gate.moveTo(GateState.Opening(viaPassword = false, lockPending = false, transientError = true))
            ui.awaitText(OPEN_ERROR_TITLE)
            ui.assertEveryClickableHasATouchTarget("open error screen")
        }

        @Test
        fun dialogs_everyClickable_hasA48dpTouchTarget() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            visitShellDialogs { name -> ui.assertEveryClickableHasATouchTarget(name) }

            for (dialog in dialogCatalog(scene.time, app.container.dispatchers)) {
                showDialog(dialog.content)
                ui.assertEveryClickableHasATouchTarget(dialog.name)
            }
        }

        @Test
        fun atTwiceTheFontScale_theOfferTheKekNoticeAndRestoreFromBackup_stayWholeAndReachable() = atFontScale(2f) {
            scene.lock.kekNotice.value = true
            scene.launch(GateState.Ready)

            ui.awaitTag(KEK_NOTICE)
            assertWholeButton(BACKUP_NOW, inside = KEK_NOTICE)
            assertWholeButton(OK, inside = KEK_NOTICE)

            scene.lock.kekNotice.value = false
            scene.lock.slotOffer.value = SlotOffer.Missing
            ui.awaitTag(SLOT_OFFER)
            assertWholeButton(SET_UP, inside = SLOT_OFFER_SETUP)
            assertWholeButton(NOT_NOW, inside = SLOT_OFFER_DISMISS)

            scene.lock.slotOffer.value = null
            scene.gate.moveTo(GateState.Corrupt)
            ui.awaitTag(RECOVERY_RESTORE)
            compose.onNodeWithTag(RECOVERY_RESTORE).performScrollTo()
            assertWholeButton(RESTORE_FROM_BACKUP, inside = RECOVERY_RESTORE)
        }

        /** A dialog with three password fields stays usable at 200 %: its scrolling body reaches every field. */
        @Test
        fun atTwiceTheFontScale_aKeyChangeDialog_reachesEveryFieldAndItsButtons() = atFontScale(2f) {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            val dialog = dialogCatalog(scene.time, app.container.dispatchers)
                .single { it.name == "key change ${KeyFlow.ChangePassword}" }
            showDialog(dialog.content)

            compose.onAllNodes(isPassword).onLast().performScrollTo().assertIsDisplayed().assertTouchTarget()
            for (button in listOf(CHANGE_PASSWORD, CANCEL)) {
                compose.onNode(hasText(button) and hasClickAction()).assertIsDisplayed().assertTouchTarget()
                val text = hasText(button) and hasAnyAncestor(hasClickAction())
                assertWhole(compose.onNode(text, useUnmergedTree = true).textLayout())
            }
        }

        /** The button with [label] inside the node tagged [inside] is shown whole and is at least 48 dp. */
        private fun assertWholeButton(label: String, inside: String) {
            val text = hasText(label) and hasAnyAncestor(hasTestTag(inside))
            assertWhole(compose.onNode(text, useUnmergedTree = true).assertIsDisplayed().textLayout())
            compose.onNode(hasText(label) and hasClickAction()).assertTouchTarget()
        }

        private companion object {
            const val KEK_NOTICE = "kek_notice"
            const val SLOT_OFFER = "slot_offer"
            const val SLOT_OFFER_SETUP = "slot_offer_setup"
            const val SLOT_OFFER_DISMISS = "slot_offer_dismiss"
            const val RECOVERY_RESTORE = "recovery_restore"

            val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

            // Literals copied from ui.md § 11.
            const val CORRUPT_TITLE = "The database is damaged"
            const val OPEN_ERROR_TITLE = "The database could not be opened"
            const val BACKUP_NOW = "Back up now"
            const val OK = "OK"
            const val SET_UP = "Set up"
            const val NOT_NOW = "Not now"
            const val RESTORE_FROM_BACKUP = "Restore from backup"
            const val CHANGE_PASSWORD = "Change password"
            const val CANCEL = "Cancel"
        }
    }

    private companion object {
        /** Laid out at twice the font scale and not cut off (ui.md § 12.5). */
        fun assertWhole(layout: TextLayoutResult) {
            assertEquals(2f, layout.layoutInput.density.fontScale)
            layout.assertNotCutOff()
        }
    }
}
