package com.doro.organizer.ui.notes

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListComposeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-22 (DEC-35, DEC-36; ui.md § 6.12, § 8.2, § 8.3): deleting a note from the editor and batch delete both
 * ask first; Cancel deletes nothing (and keeps the selection); Delete removes the notes with their links, never the
 * linked person, and shows the count; the editor closes, selection mode ends.
 */
@RunWith(AndroidJUnit4::class)
class NoteDeleteTest : ListComposeTest() {
    @Test
    fun confirmationDialog() {
        var doomed = 0L
        var kept = 0L
        var alice = 0L
        startWith {
            doomed = note("doomed", createdAt = now)
            kept = note("kept", createdAt = now - 1)
            alice = person("Alice", createdAt = now)
            linkPerson(doomed, alice)
        }
        awaitRow(doomed)
        compose.onNodeWithTag(rowTag(doomed)).performClick()
        ui.awaitText(EditorUi.EDIT_NOTE)

        editor.menu(EditorUi.DELETE_NOTE)
        ui.awaitText("Delete this note?")
        ui.awaitText("This cannot be undone.")
        compose.onNodeWithText(CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete this note?").assertDoesNotExist()
        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE id = ?", doomed))

        editor.menu(EditorUi.DELETE_NOTE)
        compose.onNodeWithText(DELETE).performClick()

        awaitRow(kept)
        awaitNoRow(doomed)
        ui.awaitText("1 note deleted")
        assertEquals(0L, count("SELECT COUNT(*) FROM notes WHERE id = ?", doomed))
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons WHERE note_id = ?", doomed))
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE id = ?", alice))
    }

    @Test
    fun batchConfirmation() {
        var a = 0L
        var b = 0L
        var kept = 0L
        var alice = 0L
        startWith {
            a = note("a", createdAt = now - 1)
            b = note("b", createdAt = now - 2)
            kept = note("kept", createdAt = now - 3)
            alice = person("Alice", createdAt = now)
            linkPerson(a, alice)
        }
        awaitRow(a)
        longPress(a)
        ui.awaitText(selectedTitle(1))
        openBatchDelete()
        ui.awaitText("Delete 1 note?")
        compose.onNodeWithText(CANCEL).performClick()

        compose.onNodeWithTag(rowTag(b)).performClick()
        ui.awaitText(selectedTitle(2))
        openBatchDelete()
        ui.awaitText("Delete 2 notes?")
        ui.awaitText("This cannot be undone.")
        compose.onNodeWithText(CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete 2 notes?").assertDoesNotExist()
        awaitRow(a)
        awaitRow(b)
        ui.awaitText(selectedTitle(2))

        openBatchDelete()
        compose.onNodeWithText(DELETE).performClick()

        awaitNoRow(a)
        awaitNoRow(b)
        ui.awaitText("2 notes deleted")
        awaitNoSelection()
        awaitRow(kept)
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons WHERE note_id = ?", a))
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE id = ?", alice))
    }

    private fun openBatchDelete() {
        tapAction(MORE_OPTIONS)
        compose.onNodeWithText(DELETE).performClick()
        compose.waitForIdle()
    }
}
