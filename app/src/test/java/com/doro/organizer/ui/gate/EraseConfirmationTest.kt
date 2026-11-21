package com.doro.organizer.ui.gate

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typed confirmation of an Erase (ui.md § 8.10, testing.md L-24): the trimmed input equals the word, any case. */
class EraseConfirmationTest {
    @Test
    fun theWord_inAnyCase_withSurroundingWhitespace_confirms() {
        listOf("ERASE", "erase", "Erase", "  ERASE ", "\tErAsE\n", "　ERASE").forEach {
            assertTrue(it, isEraseConfirmation(it, "ERASE"))
        }
    }

    @Test
    fun anythingElse_doesNotConfirm() {
        listOf("", " ", "ERAS", "ERASE!", "E RASE", "ERASEE", "delete").forEach {
            assertFalse(it, isEraseConfirmation(it, "ERASE"))
        }
    }

    @Test
    fun caseFolding_isLocaleIndependent() {
        // Under a Turkish default locale a default-locale uppercase turns "i" into "İ"; Locale.ROOT does not.
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertTrue(isEraseConfirmation("wipe", "WIPE"))
            assertTrue(isEraseConfirmation("WIPE", "wipe"))
            assertFalse(isEraseConfirmation("WİPE", "WIPE"))
        } finally {
            Locale.setDefault(default)
        }
    }
}
