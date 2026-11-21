package com.doro.organizer.ui.notes

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.pickerRowTag
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-45, the orphan recovery flow of §13.3 (DEC-27, DEC-33, DEC-34, DEC-36; ui.md § 4.3, § 5.3, § 6.11):
 * a person whose three notes have no other link is deleted from the editor's menu; Orphans then lists those three;
 * Select all, Associate with a project created in the picker; Orphans shows "(No data)" and the snackbar counts 3.
 */
@RunWith(AndroidJUnit4::class)
class OrphanFlowTest : ListComposeTest() {
    @Test
    fun deleteAPerson_thenReassociateItsOrphans() {
        val notes = mutableListOf<Long>()
        var alice = 0L
        startWith {
            alice = person("Alice", createdAt = now)
            repeat(3) { i -> notes += note("note $i", createdAt = now - i).also { linkPerson(it, alice) } }
        }
        awaitRow(notes[0])
        compose.onNodeWithTag(rowTag(notes[0])).performClick()
        ui.awaitText(EditorUi.EDIT_NOTE)

        editor.menu(EditorUi.DELETE_PERSON)
        ui.awaitTag(pickerRowTag(alice))
        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        ui.awaitText("Delete person \"Alice\"?")
        ui.awaitText(
            "This person is linked to 3 notes. All of them will have no person or project left and will move to " +
                "Orphans. This cannot be undone.",
        )
        compose.onNodeWithText(DELETE).performClick()
        ui.awaitText(EditorUi.PERSON_DELETED)
        ui.pressBack()

        openTab(Perspective.Orphan)
        notes.forEach(::awaitRow)
        longPress(notes[0])
        tapAction(SELECT_ALL)
        ui.awaitText(selectedTitle(3))
        tapAction(EditorUi.ASSOCIATE)
        editor.pickerTab(EditorUi.PROJECTS).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Alpha")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(EditorUi.ASSOCIATE).performClick()

        ui.awaitText("3 notes associated")
        ui.awaitText(NO_DATA)
        assertEquals(0L, count("SELECT COUNT(*) FROM persons"))
        assertEquals(3L, count("SELECT COUNT(*) FROM note_projects y JOIN projects j ON j.id = y.project_id WHERE j.name = 'Alpha'"))
    }
}
