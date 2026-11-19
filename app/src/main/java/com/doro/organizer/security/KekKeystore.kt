package com.doro.organizer.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Keystore half of the KEK layer, alias `doro_kek_v1` (security.md § 3.1, § 3.6): a constructor seam of
 * [DefaultKeyVault] through which tests inject the failures of § 5.2. Every member may throw the Keystore family
 * of § 5.2 (`GeneralSecurityException`, `ProviderException`, `IOException`); a missing alias fails [initEncrypt]
 * and [initDecrypt] with an `UnrecoverableKeyException`.
 */
interface KekKeystore {
    /** Whether the device reports `FEATURE_STRONGBOX_KEYSTORE`. */
    val hasStrongBox: Boolean

    fun containsAlias(): Boolean

    /** Generates the KEK (§ 3.2), StrongBox-backed when [strongBox]; replaces nothing (the alias is missing). */
    fun generate(strongBox: Boolean)

    fun delete()

    /** An ENCRYPT cipher; the Keystore picks the IV (`cipher.iv`). */
    fun initEncrypt(): Cipher

    fun initDecrypt(iv: ByteArray): Cipher

    fun doFinal(cipher: Cipher, input: ByteArray): ByteArray
}

/** The production [KekKeystore] on AndroidKeyStore. */
class AndroidKekKeystore(override val hasStrongBox: Boolean) : KekKeystore {
    override fun containsAlias(): Boolean = keyStore().containsAlias(KEK_ALIAS)

    override fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256) // the default is 128
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox) // no user-auth flags, no setUnlockedDeviceRequired (DEC-60)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply { init(spec) }.generateKey()
    }

    override fun delete() = keyStore().deleteEntry(KEK_ALIAS)

    override fun initEncrypt(): Cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }

    override fun initDecrypt(iv: ByteArray): Cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
    }

    override fun doFinal(cipher: Cipher, input: ByteArray): ByteArray = cipher.doFinal(input)

    private fun key(): SecretKey =
        keyStore().getKey(KEK_ALIAS, null) as? SecretKey ?: throw UnrecoverableKeyException("KEK missing")

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"

        fun forDevice(context: Context) =
            AndroidKekKeystore(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
    }
}
