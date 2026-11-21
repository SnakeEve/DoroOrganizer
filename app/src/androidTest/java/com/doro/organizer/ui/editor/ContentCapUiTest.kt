package com.doro.organizer.ui.editor

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListComposeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-08, the editor's input (DEC-24; ui.md § 6.9): text pasted into the content field beyond 200,000 UTF-16
 * units is cut by `ContentCap`, at 199,999 when the cut would split a surrogate pair, and the notice shows. The
 * transformation needs a composed field, so this half runs here; the cut itself is `ContentCapTest` on the JVM.
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
}
