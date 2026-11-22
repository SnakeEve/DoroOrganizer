package com.doro.organizer.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line limit's helpers (Q14, data-layer.md INV-1, ui.md § 6.9): only U+000A is a line break, which the editor's
 * text layout needs to start a new line; `capLinesLength` keeps the first lines, cutting just before the line break
 * that would start one line too many; `lineBreakCount` is what `NoteRepository.save` checks.
 */
class LineBreaksTest {
    @Test
    fun onlyU000A_isALineBreak() {
        assertEquals('\u000A', LINE_BREAK)
        assertEquals(0, lineBreakCount("a\rb\u2028c\u2029d\u000Be\u000Cf\u0085g"))
        assertEquals(3, lineBreakCount("\r\n\r\n\n"))
        assertEquals(0, lineBreakCount(""))
    }

    @Test
    fun capLinesLength_cutsJustBeforeTheLineBreakThatStartsOneLineTooMany() {
        assertEquals("one\ntwo".length, capLinesLength("one\ntwo\nthree", maxLines = 2))
        assertEquals(0, capLinesLength("\nrest", maxLines = 1))
        assertEquals(1, capLinesLength("\r\n", maxLines = 1))
    }

    @Test
    fun capLinesLength_keepsATextOfAtMostMaxLinesWhole() {
        assertEquals("one\ntwo".length, capLinesLength("one\ntwo", maxLines = 2))
        assertEquals("one\ntwo\r\u2028x".length, capLinesLength("one\ntwo\r\u2028x", maxLines = 2))
        assertEquals(0, capLinesLength("", maxLines = 1))
    }

    @Test
    fun atTheContentLimit_99999LineBreaksAreKept_andThe100000thIsCut() {
        val atTheLimit = "\n".repeat(MAX_CONTENT_LINES - 1)
        val over = atTheLimit + "\n"

        assertEquals(99_999, lineBreakCount(atTheLimit))
        assertEquals(atTheLimit.length, capLinesLength(atTheLimit, MAX_CONTENT_LINES))
        assertEquals(atTheLimit.length, capLinesLength(over, MAX_CONTENT_LINES))
    }

    @Test
    fun capCharsLength_isTheLengthCapCharsKeeps() {
        val emoji = "😀"

        assertEquals(3, capCharsLength("abc", 10))
        assertEquals(10, capCharsLength("a".repeat(11), 10))
        assertEquals(9, capCharsLength("a".repeat(9) + emoji, 10))
        assertEquals(10, capCharsLength("a".repeat(8) + emoji + "b", 10))
    }
}
