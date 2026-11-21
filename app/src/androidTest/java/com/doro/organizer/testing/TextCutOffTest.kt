package com.doro.organizer.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [assertNotCutOff] judges a text whole or cut off from what the semantics text layout keeps right (testing.md I-35):
 * a short label in a wide row, whose re-laid-out layout reports a visual overflow, is whole; a wrapped text is whole;
 * a label cut by `maxLines` fails the height check, and an ellipsized label is cut off. A label that may not wrap
 * (`softWrap = false`) fails the check its node's layout shows it by: a plain `Text(String)` is laid out again for
 * semantics at the node's maximum width, which wraps it into lines taller than the node (the height check); a
 * `Text(AnnotatedString)` gives its real one-line layout, a line wider than the node (the line-width check).
 */
@RunWith(AndroidJUnit4::class)
class TextCutOffTest : ScriptedGateComposeTest() {
    @Test
    fun aShortLabelInAWideRow_isWhole() {
        showAlone { Row(Modifier.fillMaxWidth()) { Text(SHORT) } }

        val layout = compose.onNodeWithText(SHORT, useUnmergedTree = true).textLayout()
        assertTrue("the semantics layout reports a visual overflow", layout.hasVisualOverflow)
        layout.assertNotCutOff()
    }

    @Test
    fun aTextWrappedInANarrowBox_isWhole() {
        showAlone { Box(Modifier.width(120.dp)) { Text(WRAPPED) } }

        val layout = compose.onNodeWithText(WRAPPED, useUnmergedTree = true).textLayout()
        assertTrue("wraps", layout.lineCount > 1)
        layout.assertNotCutOff()
    }

    @Test
    fun aLabelClippedAtOneLine_failsTheHeightCheck() {
        showAlone { Box(Modifier.width(64.dp)) { Text(LABEL, maxLines = 1) } }

        assertCutOffBy("cut off: lines beyond maxLines or the height clipped", layoutOf(LABEL))
    }

    @Test
    fun aPlainLabelThatMayNotWrap_failsTheHeightCheck() {
        showAlone { Box(Modifier.width(64.dp)) { Text(LABEL, softWrap = false) } }

        val layout = layoutOf(LABEL)
        // The node shows one line wider than its box; the semantics layout breaks it again at the box's width.
        assertTrue("the semantics layout wraps the label", layout.lineCount > 1)
        assertCutOffBy("cut off: lines beyond maxLines or the height clipped", layout)
    }

    @Test
    fun anAnnotatedLabelThatMayNotWrap_failsTheLineWidthCheck() {
        showAlone { Box(Modifier.width(64.dp)) { Text(AnnotatedString(LABEL), softWrap = false) } }

        val layout = layoutOf(LABEL)
        // The real layout: one line, as high as the node, so only the line-width check can catch it.
        assertEquals("one line", 1, layout.lineCount)
        assertCutOffBy("cut off: line 0 is", layout)
    }

    @Test
    fun anEllipsizedLabelInANarrowBox_isCutOff() {
        showAlone { Box(Modifier.width(64.dp)) { Text(LABEL, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

        assertThrows(AssertionError::class.java) { layoutOf(LABEL).assertNotCutOff() }
    }

    private fun layoutOf(text: String): TextLayoutResult =
        compose.onNodeWithText(text, useUnmergedTree = true).textLayout()

    private fun assertCutOffBy(check: String, layout: TextLayoutResult) {
        val failure = assertThrows(AssertionError::class.java) { layout.assertNotCutOff() }
        assertTrue(failure.message, failure.message.orEmpty().startsWith(check))
    }

    private companion object {
        const val SHORT = "OK"
        const val LABEL = "Change password"
        const val WRAPPED = "A label long enough to wrap over several lines in a narrow box"
    }
}
