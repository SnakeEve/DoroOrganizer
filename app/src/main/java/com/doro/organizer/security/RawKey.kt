package com.doro.organizer.security

/** The size of a DEK and of a password KEK (security.md § 3.1). */
internal const val SECRET_SIZE = 32

/** The size of SQLCipher's raw-key form `x'<64 hex>'`. */
internal const val RAW_KEY_SIZE = 67

/**
 * The SQLCipher raw key `x'<64 hex>'` of [dek] as 67 ASCII bytes, without a `String` (security.md § 3.4). The
 * array comes from [newSecret], `KeyVault`'s allocation seam (§ 3.6); the caller zeroes [dek].
 */
internal fun sqlcipherRawKey(dek: ByteArray, newSecret: (Int) -> ByteArray): ByteArray {
    require(dek.size == SECRET_SIZE)
    val hex = "0123456789abcdef"
    val out = newSecret(RAW_KEY_SIZE)
    out[0] = 'x'.code.toByte()
    out[1] = '\''.code.toByte()
    out[RAW_KEY_SIZE - 1] = '\''.code.toByte()
    for (i in 0 until SECRET_SIZE) {
        val v = dek[i].toInt() and 0xFF
        out[2 + 2 * i] = hex[v ushr 4].code.toByte()
        out[3 + 2 * i] = hex[v and 0x0F].code.toByte()
    }
    return out
}

/**
 * The DEK of the raw key [raw], the inverse of [sqlcipherRawKey] without a `String` (security.md § 3.7.2), in an
 * array from [newSecret]; the caller zeroes it. Anything but `x'<64 lowercase hex>'` is a caller bug.
 */
internal fun dekFromRawKey(raw: ByteArray, newSecret: (Int) -> ByteArray): ByteArray {
    require(
        raw.size == RAW_KEY_SIZE && raw[0] == 'x'.code.toByte() && raw[1] == '\''.code.toByte() &&
            raw[RAW_KEY_SIZE - 1] == '\''.code.toByte(),
    ) { "raw key" } // constant message (DEC-70)
    fun nibble(b: Byte): Int = when (val c = b.toInt()) {
        in 0x30..0x39 -> c - 0x30
        in 0x61..0x66 -> c - 0x61 + 10
        else -> throw IllegalArgumentException("raw key")
    }
    val out = newSecret(SECRET_SIZE)
    try {
        for (i in 0 until SECRET_SIZE) out[i] = ((nibble(raw[2 + 2 * i]) shl 4) or nibble(raw[3 + 2 * i])).toByte()
    } catch (e: IllegalArgumentException) {
        out.fill(0) // half a DEK is still secret
        throw e
    }
    return out
}
