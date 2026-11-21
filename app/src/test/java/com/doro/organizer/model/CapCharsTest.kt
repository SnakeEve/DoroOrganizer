package com.doro.organizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The input caps of the search field (`SearchText.MAX_TERM_CHARS`, ui.md § 3.3) and the picker field (§ 7.2). */
class CapCharsTest {
    @Test
    fun textWithinTheCap_isKept() {
        val text = "a".repeat(SearchText.MAX_TERM_CHARS)

        assertSame(text, capChars(text, SearchText.MAX_TERM_CHARS))
    }

    @Test
    fun longerText_isCutToTheCap() {
        assertEquals("a".repeat(MAX_NAME_CHARS), capChars("a".repeat(MAX_NAME_CHARS + 50), MAX_NAME_CHARS))
    }

    @Test
    fun aSurrogatePair_isNeverSplit() {
        val emoji = "😀"
        val pairAcrossTheCap = "a".repeat(9) + emoji + "b"
        val pairBeforeTheCap = "a".repeat(8) + emoji + "b"

        assertEquals("a".repeat(9), capChars(pairAcrossTheCap, 10))
        assertEquals("a".repeat(8) + emoji, capChars(pairBeforeTheCap, 10))
    }
}
