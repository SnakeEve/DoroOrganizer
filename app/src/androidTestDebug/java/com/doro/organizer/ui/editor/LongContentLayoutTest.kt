package com.doro.organizer.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.MAX_CONTENT_LINES
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
 *
 * The field still lays out its whole text, and Compose cannot lay out anything taller than 16,777,215 px: Q14's probe
 * (T22 device run B), a stored note of 199,999 line breaks and "x" opened at font scale 2, threw
 * `IllegalStateException: Size(1072 x 27400000) is out of range`. The line limit of `ContentCap` (Q14, ui.md § 6.9)
 * keeps a note at 100,000 lines: such a note opens whole at font scale 2 and lays out below the limit, while more
 * than half the limit high (so twice its lines would not fit), and takes a letter but no line break at its end; the
 * probe's note opens cut to its first 100,000 lines, with the notice, and stays stored as it was.
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

    @Test
    fun aNoteOf100000Lines_atTwiceTheFontScale_opensWhole_belowTheLayoutLimit_andTakesALetterButNoLineBreak() =
        atFontScale(2f) {
            var id = 0L
            startWith { id = note(content = FULL_NOTE, createdAt = now) }
            awaitRow(id)

            compose.onNodeWithTag(rowTag(id)).performClick()
            ui.awaitTag(EditorUi.CONTENT)
            compose.waitUntil(LAYOUT_TIMEOUT_MS) { editor.contentText() == FULL_NOTE }
            val field = compose.onNodeWithTag(EditorUi.CONTENT)
            val layout = field.textLayout()
            assertEquals(MAX_CONTENT_LINES, layout.lineCount)
            val height = layout.size.height
            assertTrue(
                "the text is $height px high, not between half of $MAX_LAYOUT_PX px and that limit",
                height > MAX_LAYOUT_PX / 2 && height <= MAX_LAYOUT_PX,
            )
            assertTrue("no notice", compose.onAllNodesWithText(EditorUi.LINES_TRUNCATED).fetchSemanticsNodes().isEmpty())

            field.performTextInputSelection(TextRange(FULL_NOTE.length))
            field.performTextInput("\n")
            compose.waitUntil(LAYOUT_TIMEOUT_MS) {
                compose.onAllNodesWithText(EditorUi.LINES_TRUNCATED).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("the line break was cut", FULL_NOTE, editor.contentText())
            field.performTextInput(LETTER)
            assertEquals(FULL_NOTE + LETTER, editor.contentText())

            awaitDetachedWork { compose.onNodeWithContentDescription(BACK).performClick() }
            awaitRow(id)
            assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE id = ? AND content = ?", id, FULL_NOTE + LETTER))
        }

    @Test
    fun theQ14ProbeNote_atTwiceTheFontScale_opensCutToItsFirst100000Lines_withTheNotice_andStaysStored() =
        atFontScale(2f) {
            var id = 0L
            startWith { id = note(content = PROBE_NOTE, createdAt = now) }
            awaitRow(id)

            compose.onNodeWithTag(rowTag(id)).performClick()
            ui.awaitTag(EditorUi.CONTENT)
            compose.waitUntil(LAYOUT_TIMEOUT_MS) {
                compose.onAllNodesWithText(EditorUi.LINES_TRUNCATED).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(LAYOUT_TIMEOUT_MS) { editor.contentText() == CUT_PROBE }
            val layout = compose.onNodeWithTag(EditorUi.CONTENT).textLayout()
            assertEquals(MAX_CONTENT_LINES, layout.lineCount)
            assertTrue("the text is ${layout.size.height} px high", layout.size.height <= MAX_LAYOUT_PX)

            awaitDetachedWork { compose.onNodeWithContentDescription(BACK).performClick() }
            awaitRow(id)
            assertEquals(
                "leaving writes nothing",
                1L,
                count("SELECT COUNT(*) FROM notes WHERE id = ? AND content = ?", id, PROBE_NOTE),
            )
        }

    private companion object {
        const val LINES = 5_000
        val LONG_NOTE = (1..LINES).joinToString("\n") { "Line $it" }
        const val TYPED = "\nTyped at the end"

        /** The largest size a Compose `Constraints` holds next to a small one (ui-unit 1.12.1 `maxAllowedForSize`). */
        const val MAX_CONSTRAINT_PX = 262_142

        /** The largest size Compose lays out (ui 1.12.1: "Each dimension must be between 0 and 16777215"). */
        const val MAX_LAYOUT_PX = 16_777_215

        /** 100,000 lines, the most a note holds (Q14): empty ones and "x", the shape of Q14's probe. */
        val FULL_NOTE = "\n".repeat(MAX_CONTENT_LINES - 1) + "x"

        /** Q14's probe note (T22 device run B): 200,000 lines, within the character limit. */
        val PROBE_NOTE = "\n".repeat(199_999) + "x"

        /** The probe note as the editor loads it: its first 100,000 lines, all empty. */
        val CUT_PROBE = "\n".repeat(MAX_CONTENT_LINES - 1)

        const val LETTER = "y"

        /** Laying out 100,000 lines takes seconds on the main thread; the waits allow for it. */
        const val LAYOUT_TIMEOUT_MS = 60_000L

        // Literal copied from ui.md § 11.
        const val BACK = "Back"
    }
}
