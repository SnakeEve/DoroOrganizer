package com.doro.organizer.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.doro.organizer.model.MAX_NAME_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The rename field's value (ui.md § 8.17, DEC-113; testing.md I-65): the caret at the end, the 200-unit cap. */
class RenameFieldTest {
    @Test
    fun theFirstValue_hasTheCaretAtTheEndOfTheName() {
        val value = renameFieldValue("Alice")

        assertEquals("Alice", value.text)
        assertEquals(TextRange(5), value.selection)
    }

    @Test
    fun aValueWithinTheCap_isKeptAsItIs() {
        val value = TextFieldValue("a".repeat(MAX_NAME_CHARS), TextRange(3, 7), composition = TextRange(3, 7))

        assertSame(value, capName(value))
    }

    @Test
    fun aPasteOverTheCap_isCutThere_andTheSelectionMovesIntoTheText() {
        val pasted = "b".repeat(250)

        val value = capName(TextFieldValue(pasted, TextRange(250)))

        assertEquals("b".repeat(MAX_NAME_CHARS), value.text)
        assertEquals(TextRange(MAX_NAME_CHARS), value.selection)
    }

    @Test
    fun aCutThatWouldSplitASurrogatePair_keepsThePairWhole_byDroppingIt() {
        val text = "c".repeat(MAX_NAME_CHARS - 1) + "😀" + "d"

        val value = capName(TextFieldValue(text, TextRange(150, text.length), composition = TextRange(190, text.length)))

        assertEquals("c".repeat(MAX_NAME_CHARS - 1), value.text)
        assertEquals(TextRange(150, MAX_NAME_CHARS - 1), value.selection)
        assertEquals(TextRange(190, MAX_NAME_CHARS - 1), value.composition)
    }

    @Test
    fun aCompositionWhollyBeyondTheCut_isDropped() {
        val text = "e".repeat(MAX_NAME_CHARS + 10)

        val value = capName(TextFieldValue(text, TextRange(text.length), composition = TextRange(MAX_NAME_CHARS + 2, text.length)))

        assertEquals(MAX_NAME_CHARS, value.text.length)
        assertEquals(null, value.composition)
    }
}
