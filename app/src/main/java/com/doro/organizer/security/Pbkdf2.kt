package com.doro.organizer.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 with a 32-byte output (RFC 8018, block 1) over the password bytes of security.md § 6.3.
 * `PBEKeySpec` is not used: its char-to-byte conversion is provider-defined, and the derived key must stay
 * reproducible for years (security.md § 3.3). The result belongs to the caller.
 */
fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    require(password.isNotEmpty()) // SecretKeySpec rejects an empty key; callers filter empty input first
    require(iterations >= 1)
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
    val u = ByteArray(PBKDF2_OUTPUT_SIZE)
    mac.update(salt)
    mac.update(byteArrayOf(0, 0, 0, 1))
    mac.doFinal(u, 0)
    val t = u.copyOf()
    repeat(iterations - 1) {
        mac.update(u)
        mac.doFinal(u, 0)
        for (i in 0 until PBKDF2_OUTPUT_SIZE) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
    }
    u.fill(0)
    return t
}

/** The iterations used for the calibration runs (security.md § 3.3). */
internal const val CALIBRATION_ITERATIONS = 50_000

/**
 * The iteration count for a calibration run of [CALIBRATION_ITERATIONS] that took [tMillis] ms: a 750 ms target,
 * rounded up to a multiple of 10,000 and clamped to 600,000..5,000,000 (the floor wins on slow devices).
 * A duration below 1 ms counts as 1 ms, so 0 gives 5,000,000 without a division by zero.
 */
fun iterationsFor(tMillis: Long): Int {
    val raw = CALIBRATION_ITERATIONS * 750L / maxOf(tMillis, 1L)
    val rounded = (raw + 9_999L) / 10_000L * 10_000L
    return rounded.coerceIn(MIN_ITERATIONS.toLong(), MAX_ITERATIONS.toLong()).toInt()
}

/** The range of `slot.kdf.iterations` (security.md § 3.5); the parser rejects every other value. */
internal const val MIN_ITERATIONS = 600_000
internal const val MAX_ITERATIONS = 5_000_000

private const val PBKDF2_OUTPUT_SIZE = 32
