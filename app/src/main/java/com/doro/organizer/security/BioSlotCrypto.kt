package com.doro.organizer.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.model.KeyMode
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The biometric half of [DefaultKeyVault] (security.md § 3.7): cipher preparation, wrap and unwrap through [keystore],
 * the deletion of biometric keys, and the in-memory counter of repeated slot failures (§ 3.7.4). Each slot has its own
 * key under a fresh alias that `bioSlot.keyAlias` names (DEC-98), so arming never touches the committed slot's key.
 * It never locks and is not thread-safe: every member is a `*Locked` helper that the vault calls while it holds its
 * single mutex, with the committed state it has just read (null for a missing or an invalid file). Nothing here
 * touches the key-state file, the backoff values or `passwordVerifiedAt`, and no Keystore failure of the biometric key
 * is thrown: each one is a result (§ 3.6).
 */
internal class BioSlotCrypto(
    private val keystore: BioKeystore,
    private val dispatchers: AppDispatchers,
    private val newSecret: (Int) -> ByteArray,
    private val random: SecureRandom,
) {
    /** Consecutive failures of the slot with this IV; memory only, so a restart gives the slot two more chances. */
    private var failures: SlotFailures? = null

    suspend fun statusLocked(state: KeyState?): BioSlotStatus {
        val bioSlot = state?.bioSlot
        if (state == null || state.slot.mode != KeyMode.PASSWORD || bioSlot == null) return BioSlotStatus.None
        return withContext(dispatchers.io) {
            try {
                if (retryOnceLocked { keystore.containsAlias(bioSlot.keyAlias) }) BioSlotStatus.Live else BioSlotStatus.Dead
            } catch (e: Exception) {
                if (!e.isKeystoreFailure()) throw e
                AppLog.d("KeyVault: biometric alias check failed: ${e.javaClass.simpleName}")
                // A slot that cannot even be looked up is not live: otherwise DEC-86's fallback from a failed password
                // KEK to the fingerprint would loop and KeyUnavailable would never be reached. Nothing is deleted.
                BioSlotStatus.Unknown
            }
        }
    }

    /** `biometricUnlockCipher` (security.md § 3.7.3 step 1). */
    suspend fun unlockCipherLocked(state: KeyState?): SlotCipherResult {
        if (state == null || state.slot.mode != KeyMode.PASSWORD) return SlotCipherResult.NoSlot
        val bioSlot = state.bioSlot
        if (bioSlot == null) {
            // A malformed member is a dead slot (§ 3.5) whose alias is unknown: every key the state does not name goes.
            if (state.deadBioSlot) deleteUnreferencedBioKeysLocked(state)
            return SlotCipherResult.NoSlot
        }
        val outerIv = checkNotNull(state.slot.outerIv) // the parser validated the PASSWORD slot
        val cipher = try {
            withContext(dispatchers.io) { decryptCipherLocked(bioSlot) } ?: return SlotCipherResult.Invalidated
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteBioKeyLocked(bioSlot.keyAlias) // the dead bioSlot stays in the file
            return SlotCipherResult.Invalidated
        } catch (e: Exception) {
            if (!e.isKeystoreFailure()) throw e
            AppLog.d("KeyVault: biometric cipher failed: ${e.javaClass.simpleName}")
            return if (failedTwiceLocked(bioSlot)) SlotCipherResult.Invalidated else SlotCipherResult.Unavailable
        }
        return SlotCipherResult.Ready(
            SlotCipher(SlotUse.Unlock, cipher, bioSlot.keyAlias, state.generation, outerIv.copyOf(), bioSlot.iv.copyOf()),
        )
    }

    /**
     * `biometricArmCipher` (security.md § 3.7.2 step 1, DEC-98): a new key under a fresh alias. The committed slot's
     * key is left alone, so an arming that never commits leaves the committed slot exactly as it was; the unused keys
     * of earlier armings, which no slot names, are deleted first (§ 3.7.5).
     */
    suspend fun armCipherLocked(state: KeyState?): SlotCipherResult {
        if (state == null || state.slot.mode != KeyMode.PASSWORD) return SlotCipherResult.NoSlot
        val outerIv = checkNotNull(state.slot.outerIv)
        deleteUnreferencedBioKeysLocked(state)
        val alias = newBioKeyAlias(random)
        return withContext(dispatchers.io) {
            try {
                keystore.generate(alias)
                val cipher = keystore.initEncrypt(alias)
                SlotCipherResult.Ready(SlotCipher(SlotUse.Arm, cipher, alias, state.generation, outerIv.copyOf(), null))
            } catch (e: Exception) {
                AppLog.d("KeyVault: biometric key generation failed: ${e.javaClass.simpleName}")
                deleteBioKeyQuietly(alias)
                SlotCipherResult.Unavailable
            }
        }
    }

    /**
     * `wrapForBiometric` (security.md § 3.7.2 step 4): wraps the DEK of [liveRawKey] with the authorized [cipher]
     * (an `Arm` cipher, checked by the vault) into a slot that names the cipher's key. Persists nothing; the caller
     * commits.
     */
    suspend fun wrapLocked(state: KeyState?, cipher: SlotCipher, liveRawKey: ByteArray): WrapResult {
        if (state == null || !preparedFor(state, cipher)) {
            deleteBioKeyLocked(cipher.keyAlias) // a key change was committed meanwhile
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
                    WrapResult.Wrapped(BioSlot(cipher.keyAlias, iv, wrapped))
                } catch (e: Exception) {
                    AppLog.d("KeyVault: biometric wrap failed: ${e.javaClass.simpleName}")
                    deleteBioKeyQuietly(cipher.keyAlias)
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
        if (bioSlot == null || !preparedFor(state, cipher) || bioSlot.keyAlias != cipher.keyAlias ||
            !bioSlot.iv.contentEquals(cipher.bioIv)
        ) {
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
            return SlotDek.Failed(UnlockResult.SlotFailed(invalidated = failedTwiceLocked(bioSlot)))
        }
        if (dead) {
            dek.fill(0)
            failures = null
            deleteBioKeyLocked(bioSlot.keyAlias) // dead: the bioSlot stays in the file until the next commit (§ 3.7.4)
            return SlotDek.Failed(UnlockResult.SlotFailed(invalidated = true))
        }
        failures = null
        return SlotDek.Released(state, dek)
    }

    /**
     * Deletes every biometric key, best effort; never touches the file. The key of [state]'s slot goes first and
     * without the listing, so a slot that must die does even when the Keystore cannot list its keys.
     */
    suspend fun deleteAllBioKeysLocked(state: KeyState?) {
        state?.bioSlot?.keyAlias?.let { deleteBioKeyLocked(it) }
        deleteBioKeysLocked(keep = null)
    }

    /**
     * Deletes every biometric key that [state] does not name (DEC-98), best effort; never touches the file. A missing
     * state names none, so every key goes.
     */
    suspend fun deleteUnreferencedBioKeysLocked(state: KeyState?) = deleteBioKeysLocked(keep = state?.bioSlot?.keyAlias)

    /**
     * Deletes the key [cipher] was armed on unless [state]'s slot names it, best effort. Only a commit writes a fresh
     * alias into the file, so a creation that ends without one leaves that key to nothing (DEC-98).
     */
    suspend fun discardArmKeyLocked(state: KeyState?, cipher: SlotCipher) {
        if (state?.bioSlot?.keyAlias != cipher.keyAlias) deleteBioKeyLocked(cipher.keyAlias)
    }

    private suspend fun deleteBioKeysLocked(keep: String?) = withContext(dispatchers.io) {
        val aliases = try {
            retryOnceLocked { keystore.aliases() }
        } catch (e: Exception) {
            if (!e.isKeystoreFailure()) throw e
            // Left to the next deletion: an arming, a startup sweep, a removal, a new-DEK commit or Erase (DEC-98).
            AppLog.d("KeyVault: biometric key listing failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        aliases.filter { it != keep }.forEach(::deleteBioKeyQuietly)
    }

    private suspend fun deleteBioKeyLocked(alias: String) = withContext(dispatchers.io) { deleteBioKeyQuietly(alias) }

    private fun preparedFor(state: KeyState, cipher: SlotCipher) =
        state.slot.mode == KeyMode.PASSWORD && state.generation == cipher.generation &&
            state.slot.outerIv.contentEquals(cipher.outerIv)

    /**
     * A DECRYPT cipher on [slot]'s key, a Keystore failure retried once after 1 s (§ 5.2); null when its alias is
     * missing. An invalidated key is never retried.
     */
    private suspend fun decryptCipherLocked(slot: BioSlot): Cipher? = retryOnceLocked {
        if (keystore.containsAlias(slot.keyAlias)) keystore.initDecrypt(slot.keyAlias, slot.iv) else null
    }

    /** Runs a Keystore [step] once more after 1 s when it fails (§ 5.2), except for an invalidated key. */
    private suspend fun <T> retryOnceLocked(step: () -> T): T = try {
        step()
    } catch (e: Exception) {
        if (e is KeyPermanentlyInvalidatedException || !e.isKeystoreFailure()) throw e
        delay(KEYSTORE_RETRY_DELAY_MS)
        step()
    }

    /**
     * Counts a failure of [slot], keyed by its IV (§ 3.7.4). The second consecutive one judges the slot dead: its key
     * is deleted and true returned.
     */
    private suspend fun failedTwiceLocked(slot: BioSlot): Boolean {
        val count = (failures?.takeIf { it.iv.contentEquals(slot.iv) }?.count ?: 0) + 1
        if (count < DEAD_AFTER_FAILURES) {
            failures = SlotFailures(slot.iv.copyOf(), count)
            return false
        }
        failures = null
        deleteBioKeyLocked(slot.keyAlias)
        return true
    }

    private fun deleteBioKeyQuietly(alias: String) {
        try {
            keystore.delete(alias)
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
