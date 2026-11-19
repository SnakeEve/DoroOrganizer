package com.doro.organizer.core.password

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.InputRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The policy half of testing.md L-03 (DEC-64, security.md § 6): instrumented, because NFC runs through `android.icu`.
 * Lengths are code points after NFC, nothing is trimmed, entries are compared after NFC, and every input array is
 * zeroed.
 */
@RunWith(AndroidJUnit4::class)
class PasswordPolicyTest {
    private fun check(first: String, second: String = first, acknowledged: Boolean = true) =
        checkNewPassword(first.toCharArray(), second.toCharArray(), acknowledged)

    private fun assertRejected(rule: InputRule, result: PasswordCheck) = assertEquals(PasswordCheck.Rejected(rule), result)

    private fun assertAccepted(result: PasswordCheck) = assertTrue("$result", result is PasswordCheck.Ok)

    @Test
    fun lengthCountsCodePointsAfterNfc() {
        assertRejected(InputRule.PasswordTooShort, check("1234567"))
        assertRejected(InputRule.PasswordTooShort, check("é".repeat(7))) // 14 chars, 7 code points
        assertRejected(InputRule.PasswordTooShort, check("😀".repeat(4))) // 8 UTF-16 units, 4 emoji
        assertAccepted(check("😀".repeat(8)))
        assertAccepted(check("é".repeat(8))) // 16 chars, 8 code points
    }

    @Test
    fun theRulesApplyInOrder() {
        assertRejected(InputRule.PasswordMalformed, check("abcdefgh\uD800", acknowledged = false)) // a lone surrogate
        assertRejected(InputRule.PasswordTooShort, check("short", "other", acknowledged = false))
        assertRejected(InputRule.PasswordMismatch, check("password one", "password two", acknowledged = false))
        assertRejected(InputRule.AcknowledgementMissing, check("password one", acknowledged = false))
    }

    @Test
    fun spacesCount_andAreNotTrimmed() {
        assertAccepted(check("   abcde"))
        assertRejected(InputRule.PasswordMismatch, check("password", "password "))
    }

    @Test
    fun entriesEqualOnlyAfterNfc_match_andGiveTheSameBytes() {
        val precomposed = "Pässwörd1"
        val decomposed = "Pässwörd1"
        assertAccepted(check(precomposed, decomposed))
        assertArrayEquals(passwordBytes(precomposed.toCharArray()), passwordBytes(decomposed.toCharArray()))
    }

    @Test
    fun everyInputArrayIsZeroed() {
        val chars = "secret password".toCharArray()
        val first = "secret password".toCharArray()
        val second = "secret password".toCharArray()
        val single = "secret password".toCharArray()

        passwordBytes(chars)
        checkNewPassword(first, second, acknowledged = true)
        checkPassword(single)

        listOf(chars, first, second, single).forEach { array -> assertTrue(array.all { it == '\u0000' }) }
    }

    @Test
    fun aSinglePassword_isCheckedForWhatItCanShowOnItsOwn() {
        assertRejected(InputRule.PasswordMalformed, checkPassword("abcdefgh\uDC00".toCharArray()))
        assertRejected(InputRule.PasswordTooShort, checkPassword("é".repeat(7).toCharArray()))
        val ok = checkPassword("Pässwörd1".toCharArray()) as PasswordCheck.Ok
        assertArrayEquals("Pässwörd1".toByteArray(Charsets.UTF_8), ok.bytes)
    }

    @Test
    fun constantTimeEquals_comparesContentAndLength() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertEquals(false, constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertEquals(false, constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
