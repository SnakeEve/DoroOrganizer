package com.doro.organizer.security

import android.content.Context
import androidx.core.util.AtomicFile
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.io.syncDirectory
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReminder
import com.doro.organizer.model.UnlockBackoff
import com.doro.organizer.settings.Settings
import com.doro.organizer.settings.SettingsStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The production [KeyVault] (security.md § 3). [stateFile] is `noBackupFilesDir/db-state.json` (DEC-80).
 *
 * Seams that only tests replace (security.md § 3.6): [kek] (the Keystore half of the KEK layer), [newSecret] (the
 * allocator of every DEK, password KEK, outer-layer plaintext and raw key, so tests can check the zeroing), [kdf]
 * (the password derivation, so tests can count derivations) and [bio] (the Keystore half of the biometric slot).
 *
 * Locking: every public member enters [locked] exactly once and calls only private `*Locked` helpers, which never
 * lock; Kotlin's Mutex is not reentrant, so a public member calling another would hang the cold start (security.md
 * § 3.6). The locked body is not cancellable, so a cancelled caller never drops a secret before it is zeroed (V16).
 */
class DefaultKeyVault(
    /** Internal for the wiring check that it lives in the same `noBackupFilesDir` as `DbFiles.noBackupDir`. */
    internal val stateFile: File,
    private val kek: KekKeystore,
    private val settings: SettingsStore,
    private val appTime: AppTime,
    private val dispatchers: AppDispatchers,
    private val newSecret: (Int) -> ByteArray = ::ByteArray,
    private val kdf: (password: ByteArray, salt: ByteArray, iterations: Int) -> ByteArray = ::pbkdf2Sha256,
    bio: BioKeystore = AndroidBioKeystore(),
) : KeyVault {
    private val mutex = Mutex()
    private val atomicFile = AtomicFile(stateFile)
    private val random = SecureRandom()
    private val biometric = BioSlotCrypto(bio, dispatchers, newSecret, random)

    /**
     * The generation whose `PASSWORD` slot failed its KEK check during a fingerprint unlock (security.md § 3.7.3).
     * The next `prepare(PASSWORD, …)` (the fingerprint reset's) checks that KEK again and replaces it once if it still
     * fails (§ 3.1, § 4.3); a KEK that works again is reused, because the committed slot still needs it.
     */
    private var brokenKekGeneration: Long? = null

    /**
     * The slot the last `prepare(PASSWORD, …)` returned: committing it means the user has just typed the new password
     * twice, which refreshes the reminder (security.md § 3.6). Memory only; cleared by the next prepare or commit.
     */
    private var freshSlot: KeySlot? = null

    /**
     * The wait answered while `SettingsStore` could not be written or read. It is not persisted (security.md § 3.6),
     * but it binds every later attempt in this process, so a failing store never yields a free extra guess.
     */
    private var memoryBackoff: Backoff? = null

    override suspend fun readState(): KeyState? = locked { readStateLocked() }

    override suspend fun unlock(password: ByteArray?): UnlockResult = locked {
        val slot = committedStateOrNullLocked()?.slot
        if (slot == null) UnlockResult.KeyFailed else openKeyLocked(slot, password)
    }

    override suspend fun openKey(slot: KeySlot, password: ByteArray?): UnlockResult = locked { openKeyLocked(slot, password) }

    override suspend fun verifyPassword(password: ByteArray): UnlockResult = locked {
        val slot = committedStateOrNullLocked()?.slot
        // Any other slot is a caller bug made harmless (security.md § 3.6).
        if (slot == null || slot.mode != KeyMode.PASSWORD) return@locked UnlockResult.KeyFailed
        when (val result = passwordKeyLocked(slot, password)) {
            is UnlockResult.Key -> {
                result.rawKey.fill(0)
                UnlockResult.Verified()
            }
            else -> result
        }
    }

    override suspend fun prepare(target: KeyMode, newPassword: ByteArray?): PreparedKey = locked {
        freshSlot = null
        when (target) {
            KeyMode.PLAIN -> {
                require(newPassword == null) { "only a PASSWORD slot takes a password" }
                PreparedKey(KeySlot(KeyMode.PLAIN, null, null, null, null), ByteArray(0))
            }
            KeyMode.DEVICE -> {
                require(newPassword == null) { "only a PASSWORD slot takes a password" }
                prepareWrappedLocked(password = null)
            }
            KeyMode.PASSWORD -> {
                require(newPassword != null && newPassword.isNotEmpty()) { "a PASSWORD slot needs a password" }
                prepareWrappedLocked(newPassword).also { freshSlot = it.slot }
            }
        }
    }

    override suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?) {
        require(bio == null || slot.mode == KeyMode.PASSWORD) { "a biometric slot needs a PASSWORD slot" }
        locked {
            val fresh = freshSlot?.sameAs(slot) == true
            freshSlot = null
            val committed = committedStateOrNullLocked()
            val onDisk = withContext(dispatchers.io) {
                val bytes = KeyStateJson.serialize(generation, slot, bio)
                // The file holds the only copy of the wrapped DEK: a serialization bug must fail here, in memory.
                check(parsesBackTo(bytes, generation, slot, bio)) { "key-state serialization does not parse back" }
                writeLocked(bytes)
                readBackLocked()
            }
            // finishWrite only logs a failed rename (db-lifecycle.md § Generation commit): the effects below apply only
            // when the file now holds these inputs; DbMaintenance's read-back decides the outcome either way.
            if (onDisk == null || !parsesBackTo(onDisk, generation, slot, bio)) return@locked
            // A commit that installs a new key configuration follows a verified password, a fingerprint reset or a
            // recovery action; one that keeps the slot (a biometric slot commit, a restore) leaves the backoff alone,
            // because a fingerprint is not a password entry (security.md § 3.6, § 5.3).
            when {
                fresh -> {
                    memoryBackoff = null
                    bestEffortWrite { settings.recordPasswordVerified(appTime.nowMillis()) }
                }
                committed == null || !committed.slot.sameAs(slot) -> {
                    memoryBackoff = null
                    bestEffortWrite { settings.setBackoff(0, 0) }
                }
            }
        }
    }

    override suspend fun eraseState() = locked {
        freshSlot = null
        memoryBackoff = null
        brokenKekGeneration = null
        val committed = committedStateOrNullLocked() // read before the file goes: it names the slot's key
        withContext(dispatchers.io) {
            atomicFile.delete() // the encrypted data is unreadable from here on
            stateFile.parentFile?.let(::syncDirectory)
            deleteKekLocked()
        }
        biometric.deleteAllBioKeysLocked(committed)
    }

    override suspend fun deleteKek() = locked { withContext(dispatchers.io) { deleteKekLocked() } }

    override suspend fun biometricSlotStatus(): BioSlotStatus = locked { biometric.statusLocked(committedStateOrNullLocked()) }

    override suspend fun biometricUnlockCipher(): SlotCipherResult =
        locked { biometric.unlockCipherLocked(committedStateOrNullLocked()) }

    override suspend fun unlockWithBiometric(cipher: SlotCipher): UnlockResult {
        // An arm cipher here would encrypt 48 bytes into 64 and kill a valid slot: a caller bug must crash (§ 3.7.3).
        require(cipher.use == SlotUse.Unlock) { "not an unlock cipher" }
        return locked {
            when (val opened = biometric.unwrapLocked(committedStateOrNullLocked(), cipher)) {
                is SlotDek.Failed -> opened.result
                is SlotDek.Released -> {
                    val rawKey = opened.dek.zeroAfter { sqlcipherRawKey(it, newSecret) }
                    // A programming error in the KEK check must not drop the raw key unzeroed (V16).
                    val kekBroken = try {
                        kekBrokenLocked(opened.state)
                    } catch (t: Throwable) {
                        rawKey.fill(0)
                        throw t
                    }
                    UnlockResult.Key(rawKey, kekBroken)
                }
            }
        }
    }

    override suspend fun verifyBiometric(cipher: SlotCipher): UnlockResult {
        require(cipher.use == SlotUse.Unlock) { "not an unlock cipher" }
        return locked {
            when (val opened = biometric.unwrapLocked(committedStateOrNullLocked(), cipher)) {
                is SlotDek.Failed -> opened.result
                is SlotDek.Released -> {
                    opened.dek.fill(0) // proven by the tag; no raw key is built
                    UnlockResult.Verified(kekBroken = kekBrokenLocked(opened.state))
                }
            }
        }
    }

    override suspend fun biometricArmCipher(): SlotCipherResult = locked { biometric.armCipherLocked(committedStateOrNullLocked()) }

    override suspend fun wrapForBiometric(cipher: SlotCipher, liveRawKey: ByteArray): WrapResult {
        // Before any Keystore call, so a caller bug is a crash and not a state change (§ 3.7.2).
        require(cipher.use == SlotUse.Arm) { "not an arm cipher" }
        return locked { biometric.wrapLocked(committedStateOrNullLocked(), cipher, liveRawKey) }
    }

    override suspend fun discardArmCipher(cipher: SlotCipher) {
        require(cipher.use == SlotUse.Arm) { "not an arm cipher" }
        locked { biometric.discardArmKeyLocked(committedStateOrNullLocked(), cipher) }
    }

    override suspend fun deleteBiometricKey() = locked { biometric.deleteAllBioKeysLocked(committedStateOrNullLocked()) }

    override suspend fun deleteUnreferencedBiometricKeys() = locked {
        // An invalid file names nothing that can be trusted, so nothing is deleted on its account (DEC-86).
        val state = try {
            readStateLocked()
        } catch (e: KeyVaultException) {
            return@locked
        }
        biometric.deleteUnreferencedBioKeysLocked(state)
    }

    override suspend fun isPasswordReminderDue(): Boolean = locked {
        // A timestamp that cannot be read counts as lost, and a lost timestamp counts as due (security.md § 5.6).
        val verifiedAt = readSettingsLocked()?.passwordVerifiedAt ?: return@locked true
        PasswordReminder.isDue(verifiedAt, appTime.nowMillis())
    }

    private suspend fun <T> locked(body: suspend () -> T): T = mutex.withLock { withContext(NonCancellable) { body() } }

    private suspend fun readStateLocked(): KeyState? = withContext(dispatchers.io) {
        val bytes = try {
            atomicFile.readFully()
        } catch (e: FileNotFoundException) {
            // Missing only without a base file, also when an interrupted first write left just `.new`; a base file
            // that cannot be opened (a directory, no permission) is unreadable, hence invalid (security.md § 3.5).
            if (stateFile.exists()) throw KeyVaultException(KeyFailure.InvalidState)
            return@withContext null
        } catch (e: IOException) {
            throw KeyVaultException(KeyFailure.InvalidState)
        }
        KeyStateJson.parse(bytes)
    }

    /** The committed state; null for a missing or an invalid file alike. */
    private suspend fun committedStateOrNullLocked(): KeyState? = try {
        readStateLocked()
    } catch (e: KeyVaultException) {
        null
    }

    private fun writeLocked(bytes: ByteArray) {
        val out = atomicFile.startWrite()
        try {
            out.write(bytes)
            atomicFile.finishWrite(out) // syncs before the rename
        } catch (e: Throwable) {
            atomicFile.failWrite(out)
            throw e
        }
    }

    /** The bytes of the base file, or null when it cannot be read. */
    private fun readBackLocked(): ByteArray? = try {
        atomicFile.readFully()
    } catch (e: IOException) {
        null
    }

    private fun parsesBackTo(bytes: ByteArray, generation: Long, slot: KeySlot, bio: BioSlot?): Boolean {
        val parsed = try {
            KeyStateJson.parse(bytes)
        } catch (e: KeyVaultException) {
            return false
        }
        val bioMatches = if (bio == null) parsed.bioSlot == null && !parsed.deadBioSlot else parsed.bioSlot?.sameAs(bio) == true
        return parsed.generation == generation && parsed.slot.sameAs(slot) && bioMatches
    }

    /** The settings, or null when the store cannot be read; logged by class only. */
    private suspend fun readSettingsLocked(): Settings? = try {
        settings.snapshot()
    } catch (e: Exception) {
        AppLog.d("KeyVault: settings read failed: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun openKeyLocked(slot: KeySlot, password: ByteArray?): UnlockResult = when (slot.mode) {
        KeyMode.PLAIN -> UnlockResult.Key(ByteArray(0))
        KeyMode.DEVICE -> {
            val dek = outerUnwrapLocked(slot)
            if (dek == null) UnlockResult.KeyFailed else dek.zeroAfter { UnlockResult.Key(sqlcipherRawKey(it, newSecret)) }
        }
        KeyMode.PASSWORD -> if (password == null) UnlockResult.PasswordNeeded else passwordKeyLocked(slot, password)
    }

    /** The steps of `openKey` for a `PASSWORD` slot, in the order of security.md § 3.6. */
    private suspend fun passwordKeyLocked(slot: KeySlot, password: ByteArray): UnlockResult {
        val now = appTime.elapsedMillis()
        val (failures, retryAt) = backoffLocked(now)
        // (1) The throttle runs before any crypto, under the mutex, so parallel attempts cannot bypass it.
        if (now < retryAt) return UnlockResult.Throttled(failures, retryAt)
        // (2) Such a password can never have been set: rejected, not counted, no PBKDF2 (DEC-62).
        if (password.isEmpty()) return UnlockResult.PasswordRejected(failures, retryAt)
        val kdfParams = slot.kdf
        val innerIv = slot.innerIv
        if (kdfParams == null || innerIv == null) return UnlockResult.KeyFailed
        // (3) The Keystore layer. A failure here never counts: it is KeyUnavailable (security.md § 5.2).
        val inner = outerUnwrapLocked(slot) ?: return UnlockResult.KeyFailed
        // (4) PBKDF2 and the inner GCM: only its tag failure is a wrong password; any other failure on the password
        // layer is KeyUnavailable (security.md § 5.2).
        val plain = try {
            deriveLocked(password, kdfParams.salt, kdfParams.iterations)
                .zeroAfter { passwordLayer(Cipher.DECRYPT_MODE, it, innerIv, inner) }
        } catch (e: AEADBadTagException) {
            return countFailureLocked(failures)
        } catch (e: Exception) {
            AppLog.d("KeyVault: password layer failed: ${e.javaClass.simpleName}")
            return UnlockResult.KeyFailed
        } finally {
            inner.fill(0)
        }
        if (plain.size != SECRET_SIZE) {
            plain.fill(0)
            return UnlockResult.KeyFailed
        }
        val rawKey = plain.intoSecret().zeroAfter { sqlcipherRawKey(it, newSecret) }
        memoryBackoff = null
        // One edit, best effort: a correct password never fails because of it; the reminder then stays due.
        bestEffortWrite { settings.recordPasswordVerified(appTime.nowMillis()) }
        return UnlockResult.Key(rawKey)
    }

    /**
     * The backoff pair in force at [now]: the stored one through the reboot clamp of security.md § 5.3 (written back
     * when it differs), raised to [memoryBackoff]. When the store cannot be read, the attempt fails closed without
     * crypto: at least the first delay applies (architecture.md § Error model: no SettingsStore exception leaves
     * KeyVault).
     */
    private suspend fun backoffLocked(now: Long): Backoff {
        val memory = memoryBackoff
        val stored = readSettingsLocked()
        if (stored == null) {
            val wait = memory?.takeIf { now < it.retryAt } ?: Backoff(memory?.failures ?: 0, now + UnlockBackoff.FIRST_DELAY_MS)
            memoryBackoff = wait
            return wait
        }
        val failures = stored.unlockFailures
        val retryAt = UnlockBackoff.effectiveRetryAt(failures, stored.unlockRetryAtElapsedMs, now)
        if (retryAt != stored.unlockRetryAtElapsedMs) bestEffortWrite { settings.setBackoff(failures, retryAt) }
        if (memory == null) return Backoff(failures, retryAt)
        return Backoff(maxOf(failures, memory.failures), maxOf(retryAt, memory.retryAt))
    }

    /**
     * Counts a wrong password and persists the pair before answering (DEC-62). If the write fails, the answer is a
     * wait computed in memory and not persisted: never a crash and never a free extra guess (security.md § 3.6).
     * The wait and the count are kept in [memoryBackoff], so the next attempt still waits and still counts up.
     */
    private suspend fun countFailureLocked(previous: Int): UnlockResult {
        val failures = previous + 1
        val now = appTime.elapsedMillis()
        val delay = UnlockBackoff.delayMillis(failures)
        val retryAt = if (delay == 0L) 0L else now + delay
        return try {
            settings.setBackoff(failures, retryAt)
            memoryBackoff = null
            UnlockResult.PasswordRejected(failures, retryAt)
        } catch (e: Exception) {
            AppLog.d("KeyVault: backoff write failed: ${e.javaClass.simpleName}")
            val wait = now + maxOf(delay, UnlockBackoff.FIRST_DELAY_MS)
            memoryBackoff = Backoff(failures, wait)
            UnlockResult.Throttled(previous, wait)
        }
    }

    /**
     * The plaintext of the outer (KEK) layer in an array from [newSecret], its length checked: the DEK for `DEVICE`
     * or the inner wrap for `PASSWORD`; the caller zeroes it. Null = KeyUnavailable. A Keystore failure is retried
     * once after 1 s; a tag failure (a damaged slot or AAD) and a wrong length never are (security.md § 5.2).
     */
    private suspend fun outerUnwrapLocked(slot: KeySlot): ByteArray? {
        val outerIv = slot.outerIv
        val wrapped = slot.wrappedDek
        if (slot.mode == KeyMode.PLAIN || outerIv == null || wrapped == null) return null
        val aad = outerAad(slot.mode, slot.kdf, slot.innerIv)
        val expected = if (slot.mode == KeyMode.DEVICE) SECRET_SIZE else SECRET_SIZE + GCM_TAG_SIZE
        return withContext(dispatchers.io) {
            try {
                val plain = retryOnce { kek.doFinal(kek.initDecrypt(outerIv).apply { updateAAD(aad) }, wrapped) }
                if (plain.size == expected) plain.intoSecret() else null.also { plain.fill(0) }
            } catch (e: AEADBadTagException) {
                null
            } catch (e: Exception) {
                if (!e.isKeystoreFailure()) throw e
                AppLog.d("KeyVault: KEK layer failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * The KEK check of a fingerprint unlock (security.md § 3.7.3): the slot never touches the KEK, so the outer layer
     * of the committed `PASSWORD` slot is unwrapped too (no password, no authentication) and its plaintext zeroed.
     * True when it fails; the generation is then remembered for the KEK replacement of the fingerprint reset, whose
     * `prepare` runs this check again. It never changes the unlock itself and is never KeyUnavailable while the slot
     * works.
     */
    private suspend fun kekBrokenLocked(state: KeyState): Boolean {
        val outer = outerUnwrapLocked(state.slot)
        if (outer == null) {
            brokenKekGeneration = state.generation
            return true
        }
        outer.fill(0)
        if (brokenKekGeneration == state.generation) brokenKekGeneration = null
        return false
    }

    /** Runs a Keystore [step] once more after 1 s when it fails, except for a tag failure (security.md § 5.2). */
    private suspend fun <T> retryOnce(step: () -> T): T = try {
        step()
    } catch (e: Exception) {
        if (e is AEADBadTagException || !e.isKeystoreFailure()) throw e
        delay(KEYSTORE_RETRY_DELAY_MS) // a retry is not an attempt: it never touches the backoff
        step()
    }

    /** New DEK, both wraps for `PASSWORD` (a non-null [password]) or the KEK wrap for `DEVICE`, and the raw key. */
    private suspend fun prepareWrappedLocked(password: ByteArray?): PreparedKey {
        val committed = committedStateOrNullLocked()
        val committedPlain = committed?.slot?.mode == KeyMode.PLAIN
        // The fingerprint reset after a kekBroken unlock: if the committed slot's outer layer still fails, the password
        // can no longer open it and the commit replaces that slot, so the KEK is replaced once instead of reused
        // (security.md § 3.1, § 4.3). The check runs again first: deleting a KEK that works would leave the committed
        // slot unreadable if this operation then fails before its commit.
        val replaceKek = password != null && committed != null && committed.generation == brokenKekGeneration &&
            kekBrokenLocked(committed)
        val dek = newSecret(SECRET_SIZE).also(random::nextBytes)
        return dek.zeroAfter {
            val mode: KeyMode
            val kdfParams: Kdf?
            val innerIv: ByteArray?
            val outerInput: ByteArray
            if (password == null) {
                mode = KeyMode.DEVICE
                kdfParams = null
                innerIv = null
                outerInput = dek
            } else {
                mode = KeyMode.PASSWORD
                innerIv = randomBytes(IV_SIZE)
                // Any failure on the password layer is KeyUnavailable (security.md § 3.2, § 5.2).
                val layer = try {
                    val params = Kdf(calibrateLocked(), randomBytes(SALT_SIZE))
                    params to deriveLocked(password, params.salt, params.iterations)
                        .zeroAfter { passwordLayer(Cipher.ENCRYPT_MODE, it, innerIv, dek).intoSecret() }
                } catch (e: Exception) {
                    AppLog.d("KeyVault: password layer failed: ${e.javaClass.simpleName}")
                    throw KeyVaultException(KeyFailure.KeyUnavailable)
                }
                kdfParams = layer.first
                outerInput = layer.second
            }
            // The inner wrap is what an offline password attack needs (security.md § 3.1): zeroed like a secret.
            val (outerIv, wrapped) = try {
                outerWrapLocked(outerAad(mode, kdfParams, innerIv), outerInput, committedPlain, replaceKek)
            } finally {
                if (outerInput !== dek) outerInput.fill(0)
            }
            // Once: a retried reset reuses the KEK made here. A replacement that failed keeps the mark for the retry.
            if (replaceKek) brokenKekGeneration = null
            PreparedKey(KeySlot(mode, outerIv, wrapped, innerIv, kdfParams), sqlcipherRawKey(dek, newSecret))
        }
    }

    /**
     * The outer wrap with the KEK lifecycle of security.md § 3.1 and § 3.2: the KEK is generated only when missing,
     * or replaced up front when [replaceKek] (a KEK found broken for the committed slot); a StrongBox KEK created here
     * that fails its self-check is replaced once by a non-StrongBox one; an existing KEK that fails is replaced only
     * while the committed mode is `PLAIN` (no committed slot uses it). Each Keystore step is retried once after 1 s
     * first (§ 5.2). Any other failure throws `KeyVaultException(KeyUnavailable)` before any export starts.
     */
    private suspend fun outerWrapLocked(
        aad: ByteArray,
        input: ByteArray,
        committedPlain: Boolean,
        replaceKek: Boolean,
    ): Pair<ByteArray, ByteArray> = withContext(dispatchers.io) {
        var origin = keystoreStep {
            when {
                replaceKek -> {
                    deleteKekLocked()
                    generateKekLocked()
                }
                kek.containsAlias() -> KekOrigin.Existing
                else -> generateKekLocked()
            }
        }
        var wrap = wrapAndCheckLocked(aad, input)
        while (wrap == null) {
            origin = when {
                origin == KekOrigin.NewStrongBox -> keystoreStep {
                    deleteKekLocked()
                    kek.generate(strongBox = false)
                    KekOrigin.New
                }
                origin == KekOrigin.Existing && committedPlain -> keystoreStep {
                    deleteKekLocked()
                    generateKekLocked()
                }
                else -> throw KeyVaultException(KeyFailure.KeyUnavailable)
            }
            wrap = wrapAndCheckLocked(aad, input)
        }
        wrap
    }

    /** The KEK of security.md § 3.2: StrongBox when available, else (or when StrongBox refuses) without it. */
    private fun generateKekLocked(): KekOrigin {
        if (kek.hasStrongBox) {
            try {
                kek.generate(strongBox = true)
                return KekOrigin.NewStrongBox
            } catch (e: ProviderException) { // includes StrongBoxUnavailableException
                deleteKekLocked()
            } catch (e: GeneralSecurityException) { // e.g. InvalidAlgorithmParameterException
                deleteKekLocked()
            }
        }
        kek.generate(strongBox = false)
        return KekOrigin.New
    }

    /** One wrap with its self-check, a Keystore failure retried once after 1 s; null when it still fails. */
    private suspend fun wrapAndCheckLocked(aad: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray>? = try {
        retryOnce { wrapOnceLocked(aad, input) }
    } catch (e: Exception) {
        if (!e.isKeystoreFailure()) throw e
        AppLog.d("KeyVault: KEK wrap failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * One wrap with the self-check of security.md § 3.2: a 12-byte IV, the expected length, and one unwrap with the
     * same KEK that reproduces [input]. Null when a check fails; a Keystore failure throws.
     */
    private fun wrapOnceLocked(aad: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray>? {
        val cipher = kek.initEncrypt()
        val iv: ByteArray? = cipher.iv
        if (iv == null || iv.size != IV_SIZE) return null
        cipher.updateAAD(aad)
        val wrapped = kek.doFinal(cipher, input)
        if (wrapped.size != input.size + GCM_TAG_SIZE) return null
        val back = kek.doFinal(kek.initDecrypt(iv).apply { updateAAD(aad) }, wrapped)
        val reproduced = back.zeroAfter { MessageDigest.isEqual(it, input) }
        return if (reproduced) iv to wrapped else null
    }

    /**
     * The iteration count for a new password (security.md § 3.3): two runs of 50,000 iterations on throwaway input,
     * the second one measured on `AppTime.elapsedMillis()`. Never at unlock, which uses the stored count.
     */
    private suspend fun calibrateLocked(): Int = withContext(dispatchers.default) {
        val input = randomBytes(SALT_SIZE)
        val salt = randomBytes(SALT_SIZE)
        var measured = 0L
        repeat(2) {
            val start = appTime.elapsedMillis()
            kdf(input, salt, CALIBRATION_ITERATIONS).fill(0)
            measured = appTime.elapsedMillis() - start
        }
        iterationsFor(measured)
    }

    /** The password KEK, allocated through [newSecret]; the caller zeroes it. */
    private suspend fun deriveLocked(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val derived = withContext(dispatchers.default) { kdf(password, salt, iterations) }.intoSecret()
        if (derived.size != SECRET_SIZE) {
            derived.fill(0)
            error("a password KEK has 32 bytes")
        }
        return derived
    }

    private fun deleteKekLocked() {
        try {
            kek.delete()
        } catch (e: Exception) {
            AppLog.d("KeyVault: KEK delete failed: ${e.javaClass.simpleName}")
        }
    }

    /** Settings writes after a decision never change the answer (security.md § 3.6). */
    private suspend fun bestEffortWrite(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            AppLog.d("KeyVault: settings write failed: ${e.javaClass.simpleName}")
        }
    }

    /** A copy of this provider-allocated secret in an array from [newSecret]; this array is zeroed. */
    private fun ByteArray.intoSecret(): ByteArray {
        val out = newSecret(size)
        try {
            copyInto(out)
        } finally {
            fill(0)
        }
        return out
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    /** A KEK step, retried once after 1 s (§ 5.2); a Keystore failure that persists is KeyUnavailable. */
    private suspend fun <T> keystoreStep(step: () -> T): T = try {
        retryOnce(step)
    } catch (e: Exception) {
        if (!e.isKeystoreFailure()) throw e
        AppLog.d("KeyVault: KEK step failed: ${e.javaClass.simpleName}")
        throw KeyVaultException(KeyFailure.KeyUnavailable)
    }

    /** Where the KEK used by a `prepare` came from. */
    private enum class KekOrigin { Existing, New, NewStrongBox }

    /** A failure count and its retry time on the `AppTime.elapsedMillis()` base. */
    private data class Backoff(val failures: Int, val retryAt: Long)

    companion object {
        /** The key-state file name in `noBackupFilesDir` (DEC-80). */
        const val STATE_FILE_NAME = "db-state.json"

        /** The production vault: `noBackupFilesDir/db-state.json` on AndroidKeyStore, with the default seams. */
        fun create(context: Context, settings: SettingsStore, appTime: AppTime, dispatchers: AppDispatchers) =
            DefaultKeyVault(
                // `noBackupFilesDir` without its getter, which creates the directory on the main thread (DbFiles.forApp).
                stateFile = File(File(context.applicationInfo.dataDir, "no_backup"), STATE_FILE_NAME),
                kek = AndroidKekKeystore.forDevice(context),
                settings = settings,
                appTime = appTime,
                dispatchers = dispatchers,
            )
    }
}

/** The wait before the one retry of a failed Keystore step (security.md § 5.2, DEC-76). */
internal const val KEYSTORE_RETRY_DELAY_MS = 1_000L

/** The Keystore failure family of security.md § 5.2; anything else is a programming error and is not classified. */
internal fun Exception.isKeystoreFailure() =
    this is GeneralSecurityException || this is ProviderException || this is IOException

/** Runs [block] on this secret and zeroes it afterwards, whatever happens. */
internal inline fun <T> ByteArray.zeroAfter(block: (ByteArray) -> T): T = try {
    block(this)
} finally {
    fill(0)
}
