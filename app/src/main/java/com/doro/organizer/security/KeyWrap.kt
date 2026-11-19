package com.doro.organizer.security

import com.doro.organizer.model.KeyMode
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The wrap format of security.md § 3.1: AES/GCM/NoPadding, 12-byte IV, 128-bit tag, ASCII AAD per layer.

internal const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
internal const val GCM_TAG_BITS = 128
internal const val GCM_TAG_SIZE = GCM_TAG_BITS / 8
internal const val IV_SIZE = 12
internal const val SALT_SIZE = 16

/** The key-state file's base64: the basic alphabet with padding (security.md § 3.5). */
internal val base64Encoder: Base64.Encoder = Base64.getEncoder()

/** The AAD of the inner (password) layer. */
private val PASSWORD_LAYER_AAD = "doro.pw.v1".toByteArray(Charsets.US_ASCII)

/**
 * The AAD of the outer (KEK) layer of a slot of [mode]. For `PASSWORD` it binds the KDF parameters and the inner IV,
 * which no GCM tag covers otherwise, so a damaged value fails the outer tag (KeyUnavailable) and never counts as a
 * wrong password. The base64 parts are re-encoded from the bytes, never taken from the file text.
 */
internal fun outerAad(mode: KeyMode, kdf: Kdf?, innerIv: ByteArray?): ByteArray {
    val text = when (mode) {
        KeyMode.DEVICE -> "doro.kek.v1:DEVICE"
        KeyMode.PASSWORD -> {
            checkNotNull(kdf); checkNotNull(innerIv)
            "doro.kek.v1:PASSWORD:$KDF_ALGORITHM:${kdf.iterations}:" +
                "${base64Encoder.encodeToString(kdf.salt)}:${base64Encoder.encodeToString(innerIv)}"
        }
        KeyMode.PLAIN -> error("a PLAIN slot has no wrap")
    }
    return text.toByteArray(Charsets.US_ASCII)
}

/** One pass of the password layer: AES-GCM under [passwordKek] in [cipherMode]; the result belongs to the caller. */
internal fun passwordLayer(cipherMode: Int, passwordKek: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
    cipher.init(cipherMode, SecretKeySpec(passwordKek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD(PASSWORD_LAYER_AAD)
    return cipher.doFinal(input)
}

/**
 * The AAD of the biometric slot: binds it to the password slot it was armed next to, whose `outerIv` is new for
 * every wrap of every DEK, so a stale `bioSlot` next to a newer slot fails its tag (security.md § 3.7.2).
 */
internal fun bioSlotAad(outerIv: ByteArray): ByteArray =
    "doro.bio.v1:${base64Encoder.encodeToString(outerIv)}".toByteArray(Charsets.US_ASCII)
