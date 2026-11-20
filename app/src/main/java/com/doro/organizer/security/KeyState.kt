package com.doro.organizer.security

import com.doro.organizer.model.KeyMode

// The key-state types of security.md § 3.6. They hold ByteArrays, so == never compares them: use sameAs. Every
// toString() prints the class name and the mode only, never array content (DEC-70).

/** The PBKDF2 parameters of a `PASSWORD` slot; the algorithm is the validated constant `PBKDF2-HMAC-SHA256`. */
class Kdf(val iterations: Int, val salt: ByteArray) {
    override fun toString() = "Kdf"
}

/** The main key slot of the key-state file (security.md § 3.5); `kekAlias` is the validated constant `doro_kek_v1`. */
class KeySlot(
    val mode: KeyMode,
    val outerIv: ByteArray?,
    val wrappedDek: ByteArray?,
    val innerIv: ByteArray?,
    val kdf: Kdf?,
) {
    /** Equal mode and byte-for-byte equal fields; a field absent on one side must be absent on the other. */
    fun sameAs(other: KeySlot): Boolean =
        mode == other.mode &&
            outerIv.contentEquals(other.outerIv) &&
            wrappedDek.contentEquals(other.wrappedDek) &&
            innerIv.contentEquals(other.innerIv) &&
            (kdf?.iterations == other.kdf?.iterations) &&
            kdf?.salt.contentEquals(other.kdf?.salt)

    override fun toString() = "KeySlot(mode=$mode)"
}

/**
 * The biometric slot (security.md § 3.7). [keyAlias] names the Keystore key its DEK was wrapped with, an alias of the
 * validated fresh-alias form ([isSlotKeyAlias]); each slot has its own key (DEC-98).
 */
class BioSlot(val keyAlias: String, val iv: ByteArray, val wrappedDek: ByteArray) {
    fun sameAs(other: BioSlot): Boolean =
        keyAlias == other.keyAlias && iv.contentEquals(other.iv) && wrappedDek.contentEquals(other.wrappedDek)

    override fun toString() = "BioSlot"
}

/**
 * The committed key-state. [bioSlot] only with `slot.mode == PASSWORD`. [deadBioSlot] is true when the file held a
 * malformed `bioSlot` that the parser ignored (security.md § 3.5); [bioSlot] is then null, so a read-back sees a
 * dead slot as "no slot".
 */
class KeyState(
    val generation: Long,
    val slot: KeySlot,
    val bioSlot: BioSlot? = null,
    val deadBioSlot: Boolean = false,
) {
    override fun toString() = "KeyState(mode=${slot.mode})"
}

/** A new key configuration from [KeyVault.prepare]; [rawKey] is `ByteArray(0)` for `PLAIN`. */
class PreparedKey(val slot: KeySlot, val rawKey: ByteArray) {
    override fun toString() = "PreparedKey(mode=${slot.mode})"
}
