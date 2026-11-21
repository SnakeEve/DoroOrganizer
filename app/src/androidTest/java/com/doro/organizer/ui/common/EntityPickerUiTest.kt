package com.doro.organizer.ui.common

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.pickerRowTag
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-23, Compose half (DEC-32, DEC-33; ui.md § 6.4, § 7.3, § 7.6): the editor's "Add person" picker lists
 * every person with "N notes · added <date>", zero-note ones included; the create row reads `Create another "X"` on a
 * case-insensitive match; a person created in a cancelled picker, or removed before the save, is never inserted,
 * while one kept is inserted once, trimmed, with the note. The repository half is `EntityPickerTest`.
 */
@RunWith(AndroidJUnit4::class)
class EntityPickerUiTest : ListComposeTest() {
    @Test
    fun thePickerListsZeroNotePeople_andOffersCreateAnotherOnAMatch() {
        var anna = 0L
        startWith { anna = person("Anna", now) }
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()

        ui.awaitText("Add people")
        ui.awaitTag(pickerRowTag(anna))
        ui.awaitText("0 notes · added 17 Nov 2026")
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("ANNA")
        ui.awaitText("Create another \"ANNA\"")
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextReplacement("Zed")
        ui.awaitText("Create \"Zed\"")
    }

    @Test
    fun aPersonCreatedInACancelledPicker_orRemovedBeforeTheSave_isNeverInserted() {
        startWith()
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Zed")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        ui.awaitText("New")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).assertDoesNotExist() // the field was cleared

        // Cancel: the created entity goes with the picker.
        compose.onNode(cancelIcon()).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        compose.onNodeWithText("Zed").assertDoesNotExist()

        // Done, then the chip removed before leaving: nothing is inserted either.
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Zed")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(DONE).performClick()
        ui.awaitText("Zed")
        compose.onNode(describedWith("Remove Zed")).performClick()
        awaitDetachedWork { leaveEditor() }

        assertEquals(0L, count("SELECT COUNT(*) FROM persons"))
        assertEquals(0L, count("SELECT COUNT(*) FROM notes"))
    }

    @Test
    fun aKeptNewPerson_isInsertedOnceAndTrimmed_withTheNote() {
        startWith()
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("  Dora  ")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(DONE).performClick()
        ui.awaitText("Dora")

        awaitDetachedWork { leaveEditor() }

        assertEquals(1L, count("SELECT COUNT(*) FROM note_persons"))
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE name = 'Dora'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM notes"))
    }

    private fun cancelIcon() = describedWith(EditorUi.CANCEL)

    /** Back from the editor, until its entry has left the composition and started its save on leave. */
    private fun leaveEditor() {
        ui.pressBack()
        ui.awaitTag("fab_create")
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithTag(EditorUi.CONTENT).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val DONE = "Done"
    }
}
