package com.doro.organizer.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import com.doro.organizer.model.MAX_CONTENT_CHARS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * testing.md I-08, the editor's JVM part (ui.md § 6.9, DEC-24): `ContentCap` as the input transformation cuts every
 * change that would exceed the limit to 200,000 UTF-16 units, or 199,999 when unit 200,000 would split a surrogate
 * pair, and calls its notice once per truncating change, never for a change within the limit; a loaded note is cut
 * the same way. The notice text and the truncation on load are `EditorViewModelRulesTest`; the field's wiring is
 * `ContentCapUiTest`.
 */
class ContentCapTest {
    @Test
    fun aChangeOverTheLimit_isCutTo200000_withOneNotice() {
        val (text, notices) = change(start = "", insert = "a".repeat(MAX_CONTENT_CHARS + 100_000))

        assertEquals("a".repeat(200_000), text)
        assertEquals(1, notices)
    }

    @Test
    fun aChangeWhoseCutWouldSplitASurrogatePair_keeps199999_withOneNotice() {
        val (text, notices) = change(start = "a".repeat(MAX_CONTENT_CHARS - 1), insert = "😀")

        assertEquals("a".repeat(199_999), text)
        assertEquals(1, notices)
    }

    @Test
    fun changesWithinTheLimit_areKept_withoutANotice() {
        val (atTheLimit, notices) = change(start = "a".repeat(MAX_CONTENT_CHARS - 2), insert = "😀")
        val (short, more) = change(start = "Call ", insert = "Bob")

        assertEquals(MAX_CONTENT_CHARS, atTheLimit.length)
        assertEquals("Call Bob", short)
        assertEquals(0, notices + more)
    }

    @Test
    fun textAtTheCap_isKeptWhole() {
        assertEquals(MAX_CONTENT_CHARS, ContentCap.keptLength("a".repeat(MAX_CONTENT_CHARS)))
        assertEquals("abc", ContentCap.capped("abc"))
    }

    @Test
    fun oneUnitOver_keeps200000() {
        val text = "a".repeat(MAX_CONTENT_CHARS + 1)

        assertEquals(200_000, ContentCap.keptLength(text))
        assertEquals("a".repeat(200_000), ContentCap.capped(text))
    }

    @Test
    fun aSurrogatePairAcrossTheCut_isDroppedWhole_keeping199999() {
        // Units 0..199,998 are 'a'; an emoji's high surrogate is unit 199,999 and its low surrogate unit 200,000.
        val text = "a".repeat(MAX_CONTENT_CHARS - 1) + "😀"

        assertEquals(199_999, ContentCap.keptLength(text))
        assertEquals("a".repeat(199_999), ContentCap.capped(text))
    }

    @Test
    fun aSurrogatePairBeforeTheCut_staysWhole() {
        val text = "a".repeat(MAX_CONTENT_CHARS - 2) + "😀" + "tail"

        assertEquals(200_000, ContentCap.keptLength(text))
        assertEquals("😀", ContentCap.capped(text).takeLast(2))
    }

    /** One edit that appends [insert] to [start], passed through the cap as the field passes it: the text and notices. */
    private fun change(start: String, insert: String): Pair<String, Int> {
        var notices = 0
        val cap = ContentCap { notices++ }
        val state = TextFieldState(start)
        state.edit {
            append(insert)
            with(cap) { transformInput() }
        }
        return state.text.toString() to notices
    }
}
