package com.doro.organizer.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** testing.md I-24 (JVM part) and the cut rule of I-27: [SearchText.toLikeTerm] and [SearchText.fold] (DEC-29). */
class SearchEscapeTest {
    @Test
    fun wildcardsAndEscapeCharacter_areEscaped() {
        assertEquals("50\\%", SearchText.toLikeTerm("50%"))
        assertEquals("a\\_b", SearchText.toLikeTerm("a_b"))
        assertEquals("c:\\\\x", SearchText.toLikeTerm("C:\\x"))
        assertEquals("\\%", SearchText.toLikeTerm("%"))
    }

    @Test
    fun input_isTrimmedAndFolded() {
        assertEquals("ärger łódź", SearchText.toLikeTerm("  ÄRGER ŁÓDŹ \n"))
    }

    @Test
    fun blankInput_meansNoFilter() {
        listOf("", " ", "\t\n", "\u2003").forEach { assertNull("<${it.length} chars>", SearchText.toLikeTerm(it)) }
    }

    @Test
    fun fold_usesTheRootLocale_notTheDefault() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertEquals("title", SearchText.fold("TITLE")) // Turkish rules would give a dotless ı
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun longInput_isCutToMaxTermChars() {
        val term = SearchText.toLikeTerm("a".repeat(60_000))

        assertEquals("a".repeat(SearchText.MAX_TERM_CHARS), term)
    }

    @Test
    fun cut_isAppliedAfterTrimming() {
        val term = SearchText.toLikeTerm(" ".repeat(10) + "b".repeat(SearchText.MAX_TERM_CHARS))

        assertEquals("b".repeat(SearchText.MAX_TERM_CHARS), term)
    }

    @Test
    fun cut_neverSplitsASurrogatePair() {
        val input = "x".repeat(SearchText.MAX_TERM_CHARS - 1) + "\uD83D\uDE00" + "tail" // emoji at units 999-1000

        assertEquals("x".repeat(SearchText.MAX_TERM_CHARS - 1), SearchText.toLikeTerm(input))
    }

    @Test
    fun escaping_appliesAfterTheCut() {
        val term = SearchText.toLikeTerm("%".repeat(SearchText.MAX_TERM_CHARS + 5))

        assertEquals("\\%".repeat(SearchText.MAX_TERM_CHARS), term)
    }
}
