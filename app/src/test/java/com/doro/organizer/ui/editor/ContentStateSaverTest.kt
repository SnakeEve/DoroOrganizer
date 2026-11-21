package com.doro.organizer.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-38, the editor content's saver (DEC-37, DEC-57; ui.md § 6.3, § 6.14): `ContentStateSaver` saves the
 * text once with the selection and no undo history, restores equal text and selection with an empty history, coerces a
 * selection beyond the text, and restores a state that `TextFieldState.Saver` wrote before the update. The saved size
 * on the device is `EditorViewModelTest.aFullDraft_isSavedOnce_belowTheBudget`.
 */
@OptIn(ExperimentalFoundationApi::class)
class ContentStateSaverTest {
    @Test
    fun save_holdsTheTextOnce_andTheSelection_withoutTheUndoHistory() {
        val state = TextFieldState()
        state.setTextAndPlaceCursorAtEnd(TEXT) // an undo step whose text after is TEXT again
        state.edit { selection = TextRange(2, 6) }
        assertTrue("the edits are undo steps", state.undoState.canUndo)

        val saved = save(ContentStateSaver, state)

        assertEquals(listOf(TEXT, 2, 6), saved)
    }

    @Test
    fun restore_givesEqualTextAndSelection_andAnEmptyUndoHistory() {
        val state = TextFieldState()
        state.setTextAndPlaceCursorAtEnd(TEXT)
        state.edit { selection = TextRange(7, 3) }

        val restored = ContentStateSaver.restore(save(ContentStateSaver, state))

        assertEquals(TEXT, restored.text.toString())
        assertEquals(TextRange(7, 3), restored.selection)
        assertFalse(restored.undoState.canUndo)
    }

    @Test
    fun restore_coercesASelectionBeyondTheText() {
        val restored = ContentStateSaver.restore(arrayListOf("abc", 5, 9))

        assertEquals("abc", restored.text.toString())
        assertEquals(TextRange(3, 3), restored.selection)
    }

    @Test
    fun restore_ofAStateThatTextFieldStateSaverWrote_keepsTextAndSelection_andDropsItsUndoHistory() {
        val state = TextFieldState()
        state.setTextAndPlaceCursorAtEnd(TEXT)
        state.edit { selection = TextRange(1, 4) }
        val legacy = save(TextFieldState.Saver, state)
        assertEquals("text, selection start and end, undo history", 4, (legacy as List<*>).size)

        val restored = ContentStateSaver.restore(legacy)

        assertEquals(TEXT, restored.text.toString())
        assertEquals(TextRange(1, 4), restored.selection)
        assertFalse(restored.undoState.canUndo)
    }

    private fun save(saver: Saver<TextFieldState, Any>, state: TextFieldState): Any =
        with(saver) { checkNotNull(ANY.save(state)) }

    private companion object {
        /** An accented letter and a surrogate pair, so the saved text is more than ASCII. */
        const val TEXT = "Call Bob é 😀"

        val ANY = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
    }
}
