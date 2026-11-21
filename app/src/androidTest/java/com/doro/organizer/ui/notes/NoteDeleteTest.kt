package com.doro.organizer.ui.notes

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.ListComposeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-22 (DEC-35, DEC-36; ui.md § 8.3): batch delete asks first; Cancel deletes nothing and keeps the
 * selection; Delete removes every selected note with its links, never the linked person, ends selection mode and
 * shows the count. The single-note dialog of the editor (`confirmationDialog`) comes with the editor (SPEC T18).
 */
@RunWith(AndroidJUnit4::class)
class NoteDeleteTest : ListComposeTest() {
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
