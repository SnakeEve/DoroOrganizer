package com.doro.organizer.testing

import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.PreparedKey
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.SlotCipherResult
import com.doro.organizer.security.SlotUse
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.security.WrapResult
import com.doro.organizer.security.sqlcipherRawKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] in memory (testing.md § 7): no Keystore, no files. Every key or slot answer is scripted per call through
 * the `next…` queues and falls back to the success of the committed mode. The raw key of `DEVICE` and `PASSWORD` is
 * SQLCipher's raw-key form of [dek], so a database keyed with `TestKey.Raw(dek)` opens with it. It records every
 * password array it receives and every raw key it returns, so tests assert their zeroing, and every call by name.
 */
class FakeKeyVault(private val dek: ByteArray = ByteArray(32) { (it + 1).toByte() }) : KeyVault {
    private val lock = Any()

    /** The committed key-state; null = no file. */
    @Volatile var state: KeyState? = null

    /** Thrown by [readState] instead of returning [state]: an invalid key-state. */
    @Volatile var readFailure: KeyVaultException? = null

    @Volatile var bioStatus = BioSlotStatus.None

    @Volatile var reminderDue = false

    val nextUnlock = ArrayDeque<UnlockResult>()
    val nextOpenKey = ArrayDeque<UnlockResult>()
    val nextVerifyPassword = ArrayDeque<UnlockResult>()
    val nextUnlockCipher = ArrayDeque<SlotCipherResult>()
    val nextArmCipher = ArrayDeque<SlotCipherResult>()
    val nextUnlockWithBiometric = ArrayDeque<UnlockResult>()
    val nextVerifyBiometric = ArrayDeque<UnlockResult>()
    val nextWrap = ArrayDeque<WrapResult>()

    /** Thrown by the next [prepare] or [commit], one per call: a key change failing before or at its commit. */
    val nextPrepareFailure = ArrayDeque<Exception>()
    val nextCommitFailure = ArrayDeque<Exception>()

    private val callLog = mutableListOf<String>()
    private val passwordArrays = mutableListOf<ByteArray>()
    private val keyArrays = mutableListOf<ByteArray>()

    /** Every member called, by name, in order. */
    val calls: List<String> get() = synchronized(lock) { callLog.toList() }

    /** Every password array a caller passed in. */
    val receivedPasswords: List<ByteArray> get() = synchronized(lock) { passwordArrays.toList() }

    /** Every raw key this fake handed out. */
    val returnedKeys: List<ByteArray> get() = synchronized(lock) { keyArrays.toList() }

    /** How often a successful password restarted the reminder (DEC-74). */
    val reminderRestarts: Int get() = calls.count { it == REMINDER_RESTART }

    /** Commits a state of [mode] for [generation], with a biometric slot when [bio] is `Live` or `Dead`. */
    fun commitMode(mode: KeyMode, generation: Long = 1, bio: BioSlotStatus = BioSlotStatus.None) {
        state = KeyState(generation, KeySlot(mode, null, null, null, null), if (bio == BioSlotStatus.None) null else BioSlot(ByteArray(12), ByteArray(48)))
        bioStatus = bio
    }

    /** A software AES-GCM cipher in the role of the authorized slot cipher, for the prompt fakes. */
    fun newSlotCipher(use: SlotUse = SlotUse.Unlock): SlotCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (use == SlotUse.Unlock) init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12))) else init(Cipher.ENCRYPT_MODE, key)
        }
        return SlotCipher(use, cipher, state?.generation ?: 1, ByteArray(12), if (use == SlotUse.Unlock) ByteArray(12) else null)
    }

    override suspend fun readState(): KeyState? {
        record("readState")
        readFailure?.let { throw it }
        return state
    }

    override suspend fun unlock(password: ByteArray?): UnlockResult {
        record("unlock")
        password?.let { synchronized(lock) { passwordArrays += it } }
        val result = nextUnlock.removeFirstOrNull() ?: defaultUnlock(password)
        if (password != null && result is UnlockResult.Key) record(REMINDER_RESTART)
        return result.tracked()
    }

    override suspend fun openKey(slot: KeySlot, password: ByteArray?): UnlockResult {
        record("openKey")
        password?.let { synchronized(lock) { passwordArrays += it } }
        return (nextOpenKey.removeFirstOrNull() ?: UnlockResult.Key(rawKeyOf(slot.mode))).tracked()
    }

    override suspend fun verifyPassword(password: ByteArray): UnlockResult {
        record("verifyPassword")
        synchronized(lock) { passwordArrays += password }
        val result = nextVerifyPassword.removeFirstOrNull()
            ?: if (state?.slot?.mode == KeyMode.PASSWORD) UnlockResult.Verified() else UnlockResult.KeyFailed
        if (result is UnlockResult.Verified) record(REMINDER_RESTART)
        return result
    }

    override suspend fun prepare(target: KeyMode, newPassword: ByteArray?): PreparedKey {
        record("prepare")
        nextPrepareFailure.removeFirstOrNull()?.let { throw it }
        return PreparedKey(KeySlot(target, null, null, null, null), rawKeyOf(target).also { keyArrays += it })
    }

    override suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?) {
        record("commit")
        nextCommitFailure.removeFirstOrNull()?.let { throw it }
        require(bio == null || slot.mode == KeyMode.PASSWORD) { "a biometric slot needs a PASSWORD slot" }
        state = KeyState(generation, slot, bio)
    }

    override suspend fun eraseState() {
        record("eraseState")
        state = null
        bioStatus = BioSlotStatus.None
    }

    override suspend fun deleteKek() = record("deleteKek")

    override suspend fun biometricSlotStatus(): BioSlotStatus {
        record("biometricSlotStatus")
        return if (state?.slot?.mode == KeyMode.PASSWORD) bioStatus else BioSlotStatus.None
    }

    override suspend fun biometricUnlockCipher(): SlotCipherResult {
        record("biometricUnlockCipher")
        return nextUnlockCipher.removeFirstOrNull() ?: when {
            state?.slot?.mode != KeyMode.PASSWORD || bioStatus == BioSlotStatus.None -> SlotCipherResult.NoSlot
            bioStatus == BioSlotStatus.Dead -> SlotCipherResult.Invalidated
            else -> SlotCipherResult.Ready(newSlotCipher(SlotUse.Unlock))
        }
    }

    override suspend fun unlockWithBiometric(cipher: SlotCipher): UnlockResult {
        record("unlockWithBiometric")
        return (nextUnlockWithBiometric.removeFirstOrNull() ?: UnlockResult.Key(rawKeyOf(KeyMode.PASSWORD))).tracked()
    }

    override suspend fun verifyBiometric(cipher: SlotCipher): UnlockResult {
        record("verifyBiometric")
        return nextVerifyBiometric.removeFirstOrNull() ?: UnlockResult.Verified()
    }

    override suspend fun biometricArmCipher(): SlotCipherResult {
        record("biometricArmCipher")
        return nextArmCipher.removeFirstOrNull() ?: if (state?.slot?.mode == KeyMode.PASSWORD) {
            SlotCipherResult.Ready(newSlotCipher(SlotUse.Arm))
        } else {
            SlotCipherResult.NoSlot
        }
    }

    override suspend fun wrapForBiometric(cipher: SlotCipher, liveRawKey: ByteArray): WrapResult {
        record("wrapForBiometric")
        return nextWrap.removeFirstOrNull() ?: WrapResult.Wrapped(BioSlot(ByteArray(12), ByteArray(48)))
    }

    override suspend fun deleteBiometricKey() {
        record("deleteBiometricKey")
        if (bioStatus == BioSlotStatus.Live) bioStatus = BioSlotStatus.Dead
    }

    override suspend fun isPasswordReminderDue(): Boolean {
        record("isPasswordReminderDue")
        return reminderDue
    }

    private fun defaultUnlock(password: ByteArray?): UnlockResult {
        val mode = state?.slot?.mode ?: return UnlockResult.KeyFailed
        return if (mode == KeyMode.PASSWORD && password == null) UnlockResult.PasswordNeeded else UnlockResult.Key(rawKeyOf(mode))
    }

    private fun rawKeyOf(mode: KeyMode): ByteArray = if (mode == KeyMode.PLAIN) ByteArray(0) else sqlcipherRawKey(dek, ::ByteArray)

    private fun UnlockResult.tracked(): UnlockResult = also { if (it is UnlockResult.Key) synchronized(lock) { keyArrays += it.rawKey } }

    private fun record(call: String) = synchronized(lock) { callLog += call }

    override fun toString() = "FakeKeyVault"

    private companion object {
        const val REMINDER_RESTART = "reminderRestart"
    }
}
