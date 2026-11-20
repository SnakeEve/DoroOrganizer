package com.doro.organizer.security

import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Keystore half of the biometric slot (security.md § 3.6, § 3.7): a constructor seam of [DefaultKeyVault] through
 * which tests drive every failure class of § 3.7.4. Each slot has its own key, named by `bioSlot.keyAlias` (DEC-98),
 * so every key operation takes its alias. Every member may throw the Keystore family of § 5.2; a missing alias fails
 * [initEncrypt] and [initDecrypt] with an `UnrecoverableKeyException`.
 */
interface BioKeystore {
    /** Every biometric alias in the Keystore ([isBioKeyAlias]); starts no key operation. */
    fun aliases(): List<String>

    /** Whether [alias] exists; starts no Keystore operation and needs no authentication. */
    fun containsAlias(alias: String): Boolean

    /** Generates a biometric key under [alias]; an existing key of that alias is replaced. */
    fun generate(alias: String)

    /** Deletes [alias]; a missing alias is not an error. */
    fun delete(alias: String)

    /** An ENCRYPT cipher on the key of [alias]; the Keystore picks the IV (`cipher.iv`). */
    fun initEncrypt(alias: String): Cipher

    /** A DECRYPT cipher on the key of [alias]; throws `KeyPermanentlyInvalidatedException` once it was invalidated. */
    fun initDecrypt(alias: String, iv: ByteArray): Cipher

    /** Adds [aad]; on an authentication-bound key only after the prompt has authorized [cipher] (§ 3.7.2). */
    fun updateAad(cipher: Cipher, aad: ByteArray)

    /** The result is provider-allocated: the caller copies a secret out of it and zeroes it. */
    fun doFinal(cipher: Cipher, input: ByteArray): ByteArray
}

/**
 * The production [BioKeystore] on AndroidKeyStore. [keySpec] is the production [bioKeySpec]; only the instrumented
 * tests pass a spec without the user-authentication parameters, so a `Cipher` works without a touch (testing.md § 5).
 */
class AndroidBioKeystore internal constructor(private val keySpec: (String) -> KeyGenParameterSpec) : BioKeystore {
    /** The production key spec; any other spec stays internal, so no production code can drop the authentication. */
    constructor() : this(::bioKeySpec)

    override fun aliases(): List<String> = keyStore().aliases().toList().filter(::isBioKeyAlias)

    override fun containsAlias(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun generate(alias: String) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply { init(keySpec(alias)) }.generateKey()
    }

    override fun delete(alias: String) = keyStore().deleteEntry(alias)

    override fun initEncrypt(alias: String): Cipher =
        Cipher.getInstance(GCM_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(alias)) }

    override fun initDecrypt(alias: String, iv: ByteArray): Cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
    }

    override fun updateAad(cipher: Cipher, aad: ByteArray) = cipher.updateAAD(aad)

    override fun doFinal(cipher: Cipher, input: ByteArray): ByteArray = cipher.doFinal(input)

    private fun key(alias: String): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: throw UnrecoverableKeyException("biometric key missing")

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}

/**
 * The production spec of the biometric key under [alias] (security.md § 3.7.1, DEC-60, DEC-98). `BiometricKeySpecTest`
 * pins every parameter by calling it; it generates nothing. TEE-backed on purpose: a StrongBox authentication-bound key
 * that fails at `doFinal` would cost a touch on every unlock with no self-check at arming.
 */
internal fun bioKeySpec(alias: String): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUserAuthenticationRequired(true)
        .apply {
            val auth = bioKeyAuth(Build.VERSION.SDK_INT)
            // bioKeyAuth chose the branch already; lint's NewApi check needs SDK_INT itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(auth.validitySeconds, checkNotNull(auth.type))
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(auth.validitySeconds)
            }
        }
        .setInvalidatedByBiometricEnrollment(true) // the platform default, set explicitly (DEC-60)
        .setIsStrongBoxBacked(false) // no setUnlockedDeviceRequired, no setUserAuthenticationValidWhileOnBody
        .build()

/**
 * The user-authentication parameters of the biometric key: every operation needs its own strong biometric.
 * [type] null means the API 28–29 call `setUserAuthenticationValidityDurationSeconds(validitySeconds)`.
 */
internal data class BioKeyAuth(val validitySeconds: Int, val type: Int?)

/**
 * API 30+: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`; API 28–29: validity −1, which means per
 * operation and biometric only. Pure, so the API 28–29 branch is pinned on the JVM (testing.md L-30, DEC-15).
 */
@SuppressLint("InlinedApi") // a compile-time constant, passed to the Keystore only on API 30+
internal fun bioKeyAuth(sdkInt: Int): BioKeyAuth =
    if (sdkInt >= Build.VERSION_CODES.R) BioKeyAuth(0, KeyProperties.AUTH_BIOMETRIC_STRONG) else BioKeyAuth(-1, null)

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
