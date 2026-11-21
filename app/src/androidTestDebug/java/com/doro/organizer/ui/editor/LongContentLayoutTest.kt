package com.doro.organizer.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.atFontScale
import com.doro.organizer.testing.textLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-56, DEC-105 (ui.md § 6.3): a stored note whose text lays out taller than any `Constraints` can carry
 * opens in the editor, takes typing at its end and saves it on leave. The content field grows only up to the viewport
 * and scrolls inside itself; a field as tall as its text made M3's `TextField` throw `IllegalArgumentException` while
 * measuring (T22 run A).
 * Short lines at twice the font size reach that height with a modest note, far below the content cap.
 */
@RunWith(AndroidJUnit4::class)
class LongContentLayoutTest : ListComposeTest() {
    @Test
    fun aNoteOfFiveThousandShortLines_atTwiceTheFontScale_opens_takesTypingAtItsEnd_andSavesIt() = atFontScale(2f) {
        var id = 0L
        startWith { id = note(content = LONG_NOTE, createdAt = now) }
        awaitRow(id)

        compose.onNodeWithTag(rowTag(id)).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        compose.waitUntil(GateUi.TIMEOUT_MS) { editor.contentText() == LONG_NOTE }
        val field = compose.onNodeWithTag(EditorUi.CONTENT)
        val textHeight = field.textLayout().size.height
        assertTrue("the text is $textHeight px high, not beyond $MAX_CONSTRAINT_PX px", textHeight > MAX_CONSTRAINT_PX)

        field.performTextInputSelection(TextRange(LONG_NOTE.length))
        field.performTextInput(TYPED)

        assertEquals(LONG_NOTE + TYPED, editor.contentText())
        field.assertIsDisplayed()
        val fieldHeight = field.fetchSemanticsNode().size.height
        val windowHeight = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("the field is $fieldHeight px high, the window $windowHeight px", fieldHeight <= windowHeight)

        awaitDetachedWork { compose.onNodeWithContentDescription(BACK).performClick() }
        awaitRow(id)
        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE id = ? AND content = ?", id, LONG_NOTE + TYPED))
    }

    private companion object {
        const val LINES = 5_000
        val LONG_NOTE = (1..LINES).joinToString("\n") { "Line $it" }
        const val TYPED = "\nTyped at the end"

        /** The largest size a Compose `Constraints` holds next to a small one (ui-unit 1.12.1 `maxAllowedForSize`). */
        const val MAX_CONSTRAINT_PX = 262_142

        // Literal copied from ui.md § 11.
        const val BACK = "Back"
    }
}
