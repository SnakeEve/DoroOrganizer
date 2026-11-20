package com.doro.organizer.security

import java.security.SecureRandom

/** The AndroidKeyStore alias of the KEK (security.md § 3.1); the key-state file accepts no other. */
internal const val KEK_ALIAS = "doro_kek_v1"

/**
 * The prefix of every biometric key alias (security.md § 3.7). Every alias with it is a biometric key of this app,
 * which the sweeps of DEC-98 delete once no committed `bioSlot` names it; that includes the constant `doro_bio_v1`
 * of builds before DEC-98.
 */
internal const val BIO_ALIAS_PREFIX = "doro_bio_v1"

/** Bytes of randomness in a fresh biometric alias: two armings never share a key (DEC-98). */
private const val BIO_ALIAS_RANDOM_BYTES = 16

/** The only `bioSlot.keyAlias` the key-state accepts (DEC-98): the prefix, `_` and 32 lowercase hex digits. */
private val SLOT_KEY_ALIAS = Regex("${BIO_ALIAS_PREFIX}_[0-9a-f]{${BIO_ALIAS_RANDOM_BYTES * 2}}")

/** Whether [alias] names a biometric key of this app, in any form ever written. */
internal fun isBioKeyAlias(alias: String): Boolean = alias.startsWith(BIO_ALIAS_PREFIX)

/** Whether [alias] has the fresh-alias form that a committed `bioSlot.keyAlias` must have (security.md § 3.5). */
internal fun isSlotKeyAlias(alias: String): Boolean = SLOT_KEY_ALIAS.matches(alias)

/** A fresh alias for the key of a new biometric slot, so arming never replaces the committed slot's key (DEC-98). */
internal fun newBioKeyAlias(random: SecureRandom): String {
    val bytes = ByteArray(BIO_ALIAS_RANDOM_BYTES).also(random::nextBytes)
    return buildString {
        append(BIO_ALIAS_PREFIX).append('_')
        bytes.forEach { append(HEX_DIGITS[(it.toInt() shr 4) and 0xf]).append(HEX_DIGITS[it.toInt() and 0xf]) }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
