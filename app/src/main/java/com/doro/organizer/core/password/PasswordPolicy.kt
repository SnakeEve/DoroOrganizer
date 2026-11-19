package com.doro.organizer.core.password

import android.icu.text.Normalizer2
import com.doro.organizer.core.result.InputRule
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

// The password policy of security.md § 6, shared by ui dialogs, session, security and data.maintenance. Every function
// zeroes the arrays it is given and every intermediate it makes; no String ever holds a password (DEC-64).

/** The minimum length in Unicode code points after NFC (security.md § 6.1). */
const val MIN_PASSWORD_CODE_POINTS = 8

/** The outcome of a password check; [Ok.bytes] belong to the caller, who zeroes them. */
sealed interface PasswordCheck {
    class Ok(val bytes: ByteArray) : PasswordCheck {
        override fun toString() = "Ok"
    }

    data class Rejected(val rule: InputRule) : PasswordCheck
}

/** CharArray -> NFC CharArray without creating a String. Zeroes its input and the intermediate StringBuilder. */
internal fun nfc(chars: CharArray): CharArray {
    val sb = StringBuilder(chars.size * 3 + 16) // no reallocation, so no stale copy is left behind
    Normalizer2.getNFCInstance().normalize(CharBuffer.wrap(chars), sb)
    val out = CharArray(sb.length).also { sb.toCharArray(it, 0, 0, sb.length) }
    for (i in sb.indices) sb.setCharAt(i, '\u0000')
    chars.fill('\u0000')
    return out
}

/** NFC chars -> UTF-8 without a String. Zeroes its input. Null for malformed input (a lone surrogate). */
internal fun utf8(nfc: CharArray): ByteArray? {
    val buf = ByteBuffer.allocate(nfc.size * 3) // UTF-8 needs at most 3 bytes per UTF-16 unit
    try {
        val encoder = StandardCharsets.UTF_8.newEncoder() // reports malformed input instead of replacing it
        if (encoder.encode(CharBuffer.wrap(nfc), buf, true).isError || encoder.flush(buf).isError) return null
        return buf.array().copyOf(buf.position())
    } finally {
        buf.array().fill(0)
        nfc.fill('\u0000')
    }
}

/** The bytes every KDF and SQLCipher passphrase receives: UTF-8 of NFC; null for malformed input. Zeroes [chars]. */
fun passwordBytes(chars: CharArray): ByteArray? = utf8(nfc(chars))

/** A new password entered twice plus the acknowledgement, in the rule order of security.md § 6.1. Zeroes both. */
fun checkNewPassword(first: CharArray, second: CharArray, acknowledged: Boolean): PasswordCheck {
    val n1 = nfc(first)
    val n2 = nfc(second)
    val length = Character.codePointCount(n1, 0, n1.size) // before utf8() zeroes n1
    val b1 = utf8(n1)
    val b2 = utf8(n2)
    try {
        val rule = when {
            b1 == null || b2 == null -> InputRule.PasswordMalformed
            length < MIN_PASSWORD_CODE_POINTS -> InputRule.PasswordTooShort
            !constantTimeEquals(b1, b2) -> InputRule.PasswordMismatch
            !acknowledged -> InputRule.AcknowledgementMissing
            else -> return PasswordCheck.Ok(b1)
        }
        b1?.fill(0)
        return PasswordCheck.Rejected(rule)
    } finally {
        b2?.fill(0)
    }
}

/**
 * The rules a single new password can show on its own (malformed, too short): what `DbMaintenance` re-checks for the
 * new database password and a backup password, whose second entry and acknowledgement the dialog has already
 * checked with [checkNewPassword]. Zeroes [chars].
 */
fun checkPassword(chars: CharArray): PasswordCheck {
    val normalized = nfc(chars)
    val length = Character.codePointCount(normalized, 0, normalized.size)
    val bytes = utf8(normalized) ?: return PasswordCheck.Rejected(InputRule.PasswordMalformed)
    if (length < MIN_PASSWORD_CODE_POINTS) {
        bytes.fill(0)
        return PasswordCheck.Rejected(InputRule.PasswordTooShort)
    }
    return PasswordCheck.Ok(bytes)
}

/**
 * Equality of two secrets in time that depends only on their lengths. `MessageDigest.isEqual` may be imported only
 * by `security` (architecture.md § Import rules), so `core` and `data.maintenance` compare with this.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
