package com.doro.organizer.data.db

import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.testing.WHITESPACE_CODE_POINTS
import com.doro.organizer.testing.kotlinWhitespaceCodePoints
import com.doro.organizer.testing.ruleWhitespaceCodePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-09 (JVM part): [WHITESPACE_CHARS] lists exactly the code points of [TextWhitespace], so the SQL
 * preview is blank exactly when the content is blank by the app's rule (DEC-24, DEC-25). On ART, where
 * `Char.isWhitespace()` differs (it counts U+180E), NoteRowTest runs the SQL preview at those code points.
 */
class WhitespaceCharsTest {
    @Test
    fun listsExactlyTheAppsWhitespaceRule() {
        assertTrue(WHITESPACE_CHARS, WHITESPACE_CHARS.startsWith("char(") && WHITESPACE_CHARS.endsWith(")"))

        assertEquals(TextWhitespace.codePoints, WHITESPACE_CODE_POINTS)
        assertEquals(TextWhitespace.codePoints, ruleWhitespaceCodePoints())
    }

    /** Where the rule comes from: the JDK's `Char.isWhitespace()`, which follows current Unicode (U+180E is Cf). */
    @Test
    fun theRule_isTheJdksCharIsWhitespace() {
        assertEquals(kotlinWhitespaceCodePoints(), TextWhitespace.codePoints)
    }
}
