package com.doro.organizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's whitespace rule (DEC-24, DEC-25, DEC-29, DEC-32, DEC-39), the same on every runtime: U+180E and the
 * other invisible non-whitespace characters are text. The device tests drive the product paths with the code points
 * where ART's `Char.isWhitespace()` differs (NoteRowTest, NoteWriteRulesTest, SearchTest). Invisible characters are
 * written as escapes, so the source shows which ones are tested.
 */
class TextWhitespaceTest {
    @Test
    fun invisibleCharactersOutsideTheSet_areText() {
        listOf(MVS, '\u0085', ZERO_WIDTH_SPACE, WORD_JOINER, BYTE_ORDER_MARK).forEach {
            assertFalse("U+%04X".format(it.code), TextWhitespace.isWhitespace(it))
        }
    }

    @Test
    fun trim_removesTheSetAtBothEnds_only() {
        assertEquals("a $MVS b", TextWhitespace.trim("\t ${IDEOGRAPHIC_SPACE}a $MVS b \n "))
        assertEquals("$MVS", TextWhitespace.trim(" $MVS "))
        assertEquals("", TextWhitespace.trim("  \r\n"))
    }

    @Test
    fun isBlank_holdsForEmptyAndWhitespaceOnly() {
        assertTrue(TextWhitespace.isBlank(""))
        assertTrue(TextWhitespace.isBlank(" \n  "))
        assertFalse(TextWhitespace.isBlank("$MVS"))
        assertFalse(TextWhitespace.isBlank(" \n x"))
    }

    @Test
    fun aSearchForU180E_isATerm() {
        assertEquals("$MVS", SearchText.toLikeTerm(" $MVS "))
        assertEquals(null, SearchText.toLikeTerm(" $IDEOGRAPHIC_SPACE "))
    }

    private companion object {
        const val MVS = '᠎' // MONGOLIAN VOWEL SEPARATOR
        const val ZERO_WIDTH_SPACE = '​'
        const val WORD_JOINER = '⁠'
        const val BYTE_ORDER_MARK = '﻿'
        const val IDEOGRAPHIC_SPACE = '　'
    }
}
