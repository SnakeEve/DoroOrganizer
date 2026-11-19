package com.doro.organizer.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.model.KeyMode
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The biometric half of [DefaultKeyVault] (security.md § 3.7): cipher preparation, wrap and unwrap through [keystore],
 * and the in-memory counter of repeated slot failures (§ 3.7.4). It never locks and is not thread-safe: every member
 * is a `*Locked` helper that the vault calls while it holds its single mutex, with the committed state it has just
 * read (null for a missing or an invalid file). Nothing here touches the key-state file, the backoff values or
 * `passwordVerifiedAt`, and no Keystore failure of the biometric key is thrown: each one is a result (§ 3.6).
 */
internal class BioSlotCrypto(
    private val keystore: BioKeystore,
    private val dispatchers: AppDispatchers,
    private val newSecret: (Int) -> ByteArray,
) {
    /** Consecutive failures of the slot with this IV; memory only, so a restart gives the slot two more chances. */
    private var failures: SlotFailures? = null

    suspend fun statusLocked(state: KeyState?): BioSlotStatus {
        if (state == null || state.slot.mode != KeyMode.PASSWORD || state.bioSlot == null) return BioSlotStatus.None
        val present = withContext(dispatchers.io) {
            try {
                retryOnceLocked { keystore.containsAlias() }
            } catch (e: Exception) {
                if (!e.isKeystoreFailure()) throw e
                AppLog.d("KeyVault: biometric alias check failed: ${e.javaClass.simpleName}")
                // A slot that cannot even be looked up is not live: otherwise DEC-86's fallback from a failed password
                // KEK to the fingerprint would loop and KeyUnavailable would never be reached. Nothing is deleted.
                false
            }
        }
        return if (present) BioSlotStatus.Live else BioSlotStatus.Dead
    }

    /** `biometricUnlockCipher` (security.md § 3.7.3 step 1). */
    suspend fun unlockCipherLocked(state: KeyState?): SlotCipherResult {
        if (state == null || state.slot.mode != KeyMode.PASSWORD) return SlotCipherResult.NoSlot
        val bioSlot = state.bioSlot
        if (bioSlot == null) {
            if (state.deadBioSlot) deleteBioKeyLocked() // a malformed member is a dead slot (§ 3.5): its key goes too
            return SlotCipherResult.NoSlot
        }
        val outerIv = checkNotNull(state.slot.outerIv) // the parser validated the PASSWORD slot
        val cipher = try {
            withContext(dispatchers.io) { decryptCipherLocked(bioSlot.iv) } ?: return SlotCipherResult.Invalidated
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteBioKeyLocked() // the dead bioSlot stays in the file
            return SlotCipherResult.Invalidated
        } catch (e: Exception) {
            if (!e.isKeystoreFailure()) throw e
            AppLog.d("KeyVault: biometric cipher failed: ${e.javaClass.simpleName}")
            return if (failedTwiceLocked(bioSlot.iv)) SlotCipherResult.Invalidated else SlotCipherResult.Unavailable
        }
        return SlotCipherResult.Ready(SlotCipher(SlotUse.Unlock, cipher, state.generation, outerIv.copyOf(), bioSlot.iv.copyOf()))
    }

    /** `biometricArmCipher` (security.md § 3.7.2 step 1): always a new key, so an older slot is dead from here on. */
    suspend fun armCipherLocked(state: KeyState?): SlotCipherResult {
        if (state == null || state.slot.mode != KeyMode.PASSWORD) return SlotCipherResult.NoSlot
        val outerIv = checkNotNull(state.slot.outerIv)
        return withContext(dispatchers.io) {
            try {
                deleteBioKeyQuietly()
                keystore.generate()
                SlotCipherResult.Ready(SlotCipher(SlotUse.Arm, keystore.initEncrypt(), state.generation, outerIv.copyOf(), null))
            } catch (e: Exception) {
                AppLog.d("KeyVault: biometric key generation failed: ${e.javaClass.simpleName}")
                deleteBioKeyQuietly()
                SlotCipherResult.Unavailable
            }
        }
    }

    /**
     * `wrapForBiometric` (security.md § 3.7.2 step 4): wraps the DEK of [liveRawKey] with the authorized [cipher]
     * (an `Arm` cipher, checked by the vault). Persists nothing; the caller commits.
     */
    suspend fun wrapLocked(state: KeyState?, cipher: SlotCipher, liveRawKey: ByteArray): WrapResult {
        if (state == null || !preparedFor(state, cipher)) {
            deleteBioKeyLocked() // a key change was committed meanwhile
            return WrapResult.BiometricUnusable
        }
        return dekFromRawKey(liveRawKey, newSecret).zeroAfter { dek ->
            withContext(dispatchers.io) {
                try {
                    // The AAD is added only now: on an authentication-bound key every step after init needs the touch.
                    keystore.updateAad(cipher.cipher, bioSlotAad(cipher.outerIv))
                    // The output is the wrapped DEK that the slot stores, not a secret: it is returned, never zeroed.
                    val wrapped = keystore.doFinal(cipher.cipher, dek)
                    val iv: ByteArray? = cipher.cipher.iv
                    check(iv != null && iv.size == IV_SIZE && wrapped.size == SECRET_SIZE + GCM_TAG_SIZE) { "slot shape" }
                    WrapResult.Wrapped(BioSlot(iv, wrapped))
                } catch (e: Exception) {
                    AppLog.d("KeyVault: biometric wrap failed: ${e.javaClass.simpleName}")
                    deleteBioKeyQuietly()
                    WrapResult.BiometricUnusable
                }
            }
        }
    }

    /**
     * The DEK through the slot (security.md § 3.7.3 steps 3 and 4) with the authorized [cipher] (an `Unlock` cipher,
     * checked by the vault). [SlotDek.Released] holds the DEK in an array from [newSecret]: the caller zeroes it.
     */
    suspend fun unwrapLocked(state: KeyState?, cipher: SlotCipher): SlotDek {
        if (state == null) return SlotDek.Failed(UnlockResult.KeyFailed) // the main path reports the file (§ 5.2)
        val bioSlot = state.bioSlot
        // A key change, a slot removal or a re-armed slot raced the prompt: the cipher stays unused, and the race is
        // not counted, so it never kills or charges the slot that is committed now.
        if (bioSlot == null || !preparedFor(state, cipher) || !bioSlot.iv.contentEquals(cipher.bioIv)) {
            return SlotDek.Failed(UnlockResult.SlotFailed(invalidated = false))
        }
        val dek = newSecret(SECRET_SIZE)
        val dead = try {
            withContext(dispatchers.io) {
                keystore.updateAad(cipher.cipher, bioSlotAad(cipher.outerIv))
                // Provider-allocated, outside newSecret: copied out and zeroed on every path.
                val plain = keystore.doFinal(cipher.cipher, bioSlot.wrappedDek)
                try {
                    (plain.size != SECRET_SIZE).also { if (!it) plain.copyInto(dek) }
                } finally {
                    plain.fill(0)
                }
            }
        } catch (e: AEADBadTagException) {
            true
        } catch (e: Exception) {
            // Never retried: a retry needs a new authentication. Only the tag is a reliable "dead" signal here.
            AppLog.d("KeyVault: biometric unwrap failed: ${e.javaClass.simpleName}")
            dek.fill(0)
            return SlotDek.Failed(UnlockResult.SlotFailed(invalidated = failedTwiceLocked(bioSlot.iv)))
        }
        if (dead) {
            dek.fill(0)
            failures = null
            deleteBioKeyLocked() // dead: the bioSlot stays in the file until the next commit (§ 3.7.4)
            return SlotDek.Failed(UnlockResult.SlotFailed(invalidated = true))
        }
        failures = null
        return SlotDek.Released(state, dek)
    }

    /** Deletes the alias `doro_bio_v1`, best effort; never touches the file. */
    suspend fun deleteBioKeyLocked() = withContext(dispatchers.io) { deleteBioKeyQuietly() }

    private fun preparedFor(state: KeyState, cipher: SlotCipher) =
        state.slot.mode == KeyMode.PASSWORD && state.generation == cipher.generation &&
            state.slot.outerIv.contentEquals(cipher.outerIv)

    /**
     * A DECRYPT cipher on the slot's key, a Keystore failure retried once after 1 s (§ 5.2); null when the alias is
     * missing. An invalidated key is never retried.
     */
    private suspend fun decryptCipherLocked(iv: ByteArray): Cipher? =
        retryOnceLocked { if (keystore.containsAlias()) keystore.initDecrypt(iv) else null }

    /** Runs a Keystore [step] once more after 1 s when it fails (§ 5.2), except for an invalidated key. */
    private suspend fun <T> retryOnceLocked(step: () -> T): T = try {
        step()
    } catch (e: Exception) {
        if (e is KeyPermanentlyInvalidatedException || !e.isKeystoreFailure()) throw e
        delay(KEYSTORE_RETRY_DELAY_MS)
        step()
    }

    /**
     * Counts a failure of the slot with [iv] (§ 3.7.4). The second consecutive one judges the slot dead: the alias is
     * deleted and true returned.
     */
    private suspend fun failedTwiceLocked(iv: ByteArray): Boolean {
        val count = (failures?.takeIf { it.iv.contentEquals(iv) }?.count ?: 0) + 1
        if (count < DEAD_AFTER_FAILURES) {
            failures = SlotFailures(iv.copyOf(), count)
            return false
        }
        failures = null
        deleteBioKeyLocked()
        return true
    }

    private fun deleteBioKeyQuietly() {
        try {
            keystore.delete()
        } catch (e: Exception) {
            // The next biometricUnlockCipher finds the key unusable and deletes it again (§ 3.7.4).
            AppLog.d("KeyVault: biometric key delete failed: ${e.javaClass.simpleName}")
        }
    }

    private class SlotFailures(val iv: ByteArray, val count: Int)

    private companion object {
        const val DEAD_AFTER_FAILURES = 2
    }
}

/** The outcome of [BioSlotCrypto.unwrapLocked]. */
internal sealed interface SlotDek {
    /** The slot released [dek] for the committed [state]; the receiver zeroes [dek]. */
    class Released(val state: KeyState, val dek: ByteArray) : SlotDek {
        override fun toString() = "Released"
    }

    class Failed(val result: UnlockResult) : SlotDek {
        override fun toString() = "Failed($result)"
    }
}
