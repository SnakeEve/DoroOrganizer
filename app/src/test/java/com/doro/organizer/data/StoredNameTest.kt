package com.doro.organizer.data

import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.testing.SENTINEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The one name rule of INV-9 that creation and rename share (DEC-32, DEC-113; testing.md I-65). On the JVM Kotlin's
 * `trim()` agrees with `TextWhitespace` at U+180E, so this class pins the rule; only `EntityRenameTest` (i) on the
 * device catches a `trim()` regression (V38).
 */
class StoredNameTest {
    @Test
    fun aName_isTrimmedByTextWhitespace() {
        assertEquals("Alicia", storedName("  Alicia\u2003\u00A0\t"))
        assertEquals("Ann Lee", storedName("\u3000Ann Lee\n"))
    }

    @Test
    fun u180e_isNotWhitespace_andStays() {
        val kept = storedName("\u180Ey\u180E")

        assertEquals("U+180E kept on both sides", listOf(0x180E, 'y'.code, 0x180E), kept?.map { it.code })
    }

    @Test
    fun aBlankName_isNull() {
        listOf("", " ", "\u2003\u00A0", "\t\n").forEach { assertNull(storedName(it)) }
    }

    @Test
    fun twoHundredUnitsAfterTheTrim_areKept() {
        val name = "x".repeat(MAX_NAME_CHARS)

        assertEquals(name, storedName("  $name  "))
    }

    @Test
    fun moreThanTwoHundredUnits_failWithAConstantMessage_thatHoldsNoName() {
        val name = SENTINEL.repeat(MAX_NAME_CHARS / SENTINEL.length + 1).take(MAX_NAME_CHARS + 1)

        val failure = assertThrows(IllegalArgumentException::class.java) { storedName(name) }

        assertEquals("name longer than 200 chars", failure.message)
        assertFalse(failure.message.orEmpty().contains(SENTINEL)) // DEC-70
    }
}
