package com.doro.organizer.data.db

import com.doro.organizer.testing.WHITESPACE_CODE_POINTS
import com.doro.organizer.testing.kotlinWhitespaceCodePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-09 (JVM part): [WHITESPACE_CHARS] lists exactly the code points for which `Char.isWhitespace()` is
 * true, so the SQL preview is blank exactly when the content is blank in Kotlin terms (DEC-24, DEC-25). NoteRowTest
 * repeats the comparison on ART.
 */
class WhitespaceCharsTest {
    @Test
    fun listsExactlyKotlinWhitespace() {
        assertTrue(WHITESPACE_CHARS, WHITESPACE_CHARS.startsWith("char(") && WHITESPACE_CHARS.endsWith(")"))

        assertEquals(kotlinWhitespaceCodePoints(), WHITESPACE_CODE_POINTS)
    }
}
