package com.doro.organizer.ui.editor

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.MAX_CONTENT_LINES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * testing.md I-08, the editor's JVM part (ui.md § 6.9; DEC-24, Q14): `ContentCap` as the input transformation cuts
 * every change that would exceed a limit, to 200,000 UTF-16 units (199,999 when unit 200,000 would split a surrogate
 * pair) or to the first 100,000 lines (just before the 100,000th line break, U+000A only); when both limits cut, the
 * shorter text is kept and only the notice of the limit that cut it is called, the character limit's on an equal cut.
 * It calls one notice per truncating change and none within the limits; a loaded note is cut the same way
 * (`ContentCap.cut`). The notice texts and the cut on load are `EditorViewModelRulesTest`; the field's wiring is
 * `ContentCapUiTest`.
 */
class ContentCapTest {
    @Test
    fun aChangeOverTheLimit_isCutTo200000_withOneNotice() {
        val (text, notices) = change(start = "", insert = "a".repeat(MAX_CONTENT_CHARS + 100_000))

        assertEquals("a".repeat(200_000), text)
        assertEquals(listOf(ContentLimit.Chars), notices)
    }

    @Test
    fun aChangeWhoseCutWouldSplitASurrogatePair_keeps199999_withOneNotice() {
        val (text, notices) = change(start = "a".repeat(MAX_CONTENT_CHARS - 1), insert = "😀")

        assertEquals("a".repeat(199_999), text)
        assertEquals(listOf(ContentLimit.Chars), notices)
    }

    @Test
    fun changesWithinTheLimits_areKept_withoutANotice() {
        val (atTheLimit, notices) = change(start = "a".repeat(MAX_CONTENT_CHARS - 2), insert = "😀")
        val (atTheLineLimit, lineNotices) = change(start = "\n".repeat(99_998), insert = "\nlast line")
        val (short, more) = change(start = "Call ", insert = "Bob")

        assertEquals(MAX_CONTENT_CHARS, atTheLimit.length)
        assertEquals("\n".repeat(99_999) + "last line", atTheLineLimit)
        assertEquals("Call Bob", short)
        assertEquals(emptyList<ContentLimit>(), notices + lineNotices + more)
    }

    @Test
    fun textAtTheCaps_isKeptWhole() {
        assertNull(ContentCap.cut("a".repeat(MAX_CONTENT_CHARS)))
        assertNull(ContentCap.cut("\n".repeat(MAX_CONTENT_LINES - 1) + "a".repeat(MAX_CONTENT_CHARS - 99_999)))
        assertNull(ContentCap.cut("abc"))
        assertNull(ContentCap.cut(""))
    }

    @Test
    fun oneUnitOver_keeps200000() {
        assertEquals(ContentCut(200_000, ContentLimit.Chars), ContentCap.cut("a".repeat(MAX_CONTENT_CHARS + 1)))
    }

    @Test
    fun aSurrogatePairAcrossTheCut_isDroppedWhole_keeping199999() {
        // Units 0..199,998 are 'a'; an emoji's high surrogate is unit 199,999 and its low surrogate unit 200,000.
        val text = "a".repeat(MAX_CONTENT_CHARS - 1) + "😀"

        assertEquals(ContentCut(199_999, ContentLimit.Chars), ContentCap.cut(text))
    }

    @Test
    fun aSurrogatePairBeforeTheCut_staysWhole() {
        val text = "a".repeat(MAX_CONTENT_CHARS - 2) + "😀" + "tail"

        assertEquals(ContentCut(200_000, ContentLimit.Chars), ContentCap.cut(text))
        assertEquals("😀", text.substring(0, 200_000).takeLast(2))
    }

    @Test
    fun aLineBreakThatWouldStartLine100001_isCut_withOneLineNotice() {
        val full = "x\n".repeat(99_999) + "x" // 199,999 units, 100,000 lines

        val (text, notices) = change(start = full, insert = "\n") // Enter at the end: 200,000 units, 100,001 lines

        assertEquals(full, text)
        assertEquals(listOf(ContentLimit.Lines), notices)
    }

    @Test
    fun aPasteOverTheLineLimit_keepsTheFirst100000LinesOfTheWholeText_withOneLineNotice() {
        val (appended, notices) = change(start = "Title", insert = "\n".repeat(150_000))
        val (inTheMiddle, more) = change(start = "head\ntail") { replace(4, 4, "\n".repeat(MAX_CONTENT_LINES)) }

        assertEquals("Title" + "\n".repeat(99_999), appended)
        assertEquals("the lines after the cut go with it", "head" + "\n".repeat(99_999), inTheMiddle)
        assertEquals(listOf(ContentLimit.Lines, ContentLimit.Lines), notices + more)
    }

    @Test
    fun aChangeOverBothLimits_keepsTheShorterText_withOnlyTheNoticeOfTheLimitThatCutIt() {
        // 300,001 units and 150,000 line breaks: the 100,000th line break, unit 199,999, comes before unit 200,000.
        val (byLines, lineNotices) = change(start = "a", insert = "\nb".repeat(150_000))
        // 350,000 units and 100,000 line breaks, all after unit 250,000: the character limit cuts first.
        val (byChars, charNotices) = change(start = "", insert = "a".repeat(250_000) + "\n".repeat(MAX_CONTENT_LINES))

        assertEquals("a" + "\nb".repeat(99_999), byLines)
        assertEquals(listOf(ContentLimit.Lines), lineNotices)
        assertEquals("a".repeat(200_000), byChars)
        assertEquals(listOf(ContentLimit.Chars), charNotices)
    }

    @Test
    fun anEqualCutByBothLimits_namesTheCharacterLimit() {
        // 99,999 line breaks in the first 199,998 units, then "ab", then the 100,000th line break as unit 200,000.
        val text = "a\n".repeat(99_999) + "ab" + "\nc"

        val (kept, notices) = change(start = "", insert = text)

        assertEquals(ContentCut(200_000, ContentLimit.Chars), ContentCap.cut(text))
        assertEquals(text.substring(0, 200_000), kept)
        assertEquals(listOf(ContentLimit.Chars), notices)
    }

    @Test
    fun onlyU000ACounts_soCarriageReturnsAndOtherSeparatorsNeverCut_andCrLfIsOneLineBreak() {
        val others = "\r\u2028\u2029\u000B\u000C\u0085" // start no line in the editor's layout (ui.md § 6.9)
        val crLf = "\r\n".repeat(MAX_CONTENT_LINES) // 200,000 units, 100,001 lines

        assertNull(ContentCap.cut("\n".repeat(99_999) + others.repeat(10_000)))
        assertNull(ContentCap.cut(others.repeat(30_000)))
        assertEquals(ContentCut(199_999, ContentLimit.Lines), ContentCap.cut(crLf)) // keeps the CR of line 100,000
    }

    @Test
    fun theStoredNoteOfTheT22Probe_isCutToItsFirst100000Lines() {
        // Q14's probe: 199,999 line breaks and "x" crashed the editor at 200 % font (T22 device run B).
        assertEquals(ContentCut(99_999, ContentLimit.Lines), ContentCap.cut("\n".repeat(199_999) + "x"))
    }

    /** One edit that appends [insert] to [start], passed through the cap as the field passes it: the text and notices. */
    private fun change(start: String, insert: String): Pair<String, List<ContentLimit>> =
        change(start) { append(insert) }

    /** One [edit] of a field holding [start], passed through the cap as the field passes it: the text and notices. */
    private fun change(start: String, edit: TextFieldBuffer.() -> Unit): Pair<String, List<ContentLimit>> {
        val notices = mutableListOf<ContentLimit>()
        val cap = ContentCap { notices += it }
        val state = TextFieldState(start)
        state.edit {
            edit()
            with(cap) { transformInput() }
        }
        return state.text.toString() to notices
    }
}
