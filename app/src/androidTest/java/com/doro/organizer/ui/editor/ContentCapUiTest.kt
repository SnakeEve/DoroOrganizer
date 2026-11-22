package com.doro.organizer.ui.editor

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.textLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-08, the editor's input (ui.md § 6.9; DEC-24, Q14): text pasted into the content field beyond 200,000
 * UTF-16 units is cut by `ContentCap`, at 199,999 when the cut would split a surrogate pair, and text beyond 100,000
 * lines is cut to its first 100,000 lines; each cut shows the notice of its limit. The transformation needs a composed
 * field, so this half runs here; the cut itself is `ContentCapTest` on the JVM. The line limit counts U+000A only,
 * because in the field's own layout on the device only U+000A starts a new line (the last test).
 */
@RunWith(AndroidJUnit4::class)
class ContentCapUiTest : ListComposeTest() {
    @Test
    fun pastingBeyondTheCap_keeps200000Units_andShowsTheNotice() {
        startWith()
        editor.createNote()

        compose.onNodeWithTag(EditorUi.CONTENT).performTextReplacement("a".repeat(200_001))

        ui.awaitText(EditorUi.TRUNCATED)
        assertEquals(200_000, editor.contentText().length)
    }

    @Test
    fun aSurrogatePairAcrossTheCut_isDroppedWhole() {
        startWith()
        editor.createNote()

        compose.onNodeWithTag(EditorUi.CONTENT).performTextReplacement("a".repeat(199_999) + "😀")

        ui.awaitText(EditorUi.TRUNCATED)
        assertEquals("a".repeat(199_999), editor.contentText())
    }

    @Test
    fun pastingBeyondTheLineLimit_keepsTheFirst100000Lines_andShowsTheLineNotice() {
        startWith()
        editor.createNote()

        // 200,000 units, within the character limit, and 100,000 line breaks: one line too many.
        compose.onNodeWithTag(EditorUi.CONTENT).performTextReplacement("x\n".repeat(100_000))

        ui.awaitText(EditorUi.LINES_TRUNCATED)
        assertEquals("x\n".repeat(99_999) + "x", editor.contentText())
    }

    /**
     * What the line limit counts (ui.md § 6.9): U+000A starts a new line in the content field's layout, and U+000D,
     * U+000B, U+000C, U+0085, U+2028 and U+2029 do not. If an Android update made one of them start a line, this
     * fails, and the cap would have to count it.
     */
    @Test
    fun inTheContentFieldsLayout_onlyU000A_startsANewLine() {
        startWith()
        editor.createNote()
        val field = compose.onNodeWithTag(EditorUi.CONTENT)

        field.performTextReplacement("a\rb\u000Bc\u000Cd\u0085e\u2028f\u2029g")
        compose.waitForIdle()
        val others = field.textLayout().lineCount
        field.performTextReplacement("a\nb")
        compose.waitForIdle()
        val lineFeed = field.textLayout().lineCount

        assertEquals("lines of a text without U+000A", 1, others)
        assertEquals("lines of a text with one U+000A", 2, lineFeed)
    }
}
