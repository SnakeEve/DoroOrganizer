package com.doro.organizer.session

import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.SqlcipherEngine
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.StartupCompletion
import com.doro.organizer.data.maintenance.StartupDecision
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockBackoff
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Effects E1–E4 of architecture.md § States, events and effects, each returning the event it raises. They run on the
 * IO dispatcher, one at a time in the order the reducer asked for them (`DefaultSessionGate`).
 */
internal class GateEffects(
    private val holder: DatabaseHolder,
    private val maintenance: DbMaintenance,
    private val keyVault: KeyVault,
    private val lockController: AppLockController,
    private val settingsStore: SettingsStore,
    private val appTime: AppTime,
) {
    /** E1. Any exception other than the classified ones is a bug and crashes the app. */
    suspend fun runStartup(): GateEvent {
        SqlcipherEngine.init()
        settingsStore.settings.first { it != null }
        val decision = maintenance.reconcileAtStartup()
        // Read after the reconciliation: the first-run reset may have changed them.
        val settings = settingsStore.snapshot()
        val lockOn = settings.biometricLock
        val failures = settings.unlockFailures
        val retryAt = effectiveRetryAt(failures, settings.unlockRetryAtElapsedMs)
        return when (decision) {
            is StartupDecision.Proceed -> {
                // Never a prompt and never a Keystore operation here (architecture.md E1 step 5).
                val route = if (decision.mode == KeyMode.PASSWORD) lockController.unlockRoute() else UnlockRoute.Biometric
                GateEvent.StartupLoaded(decision.mode, lockOn, failures, retryAt, route)
            }
            StartupDecision.FirstRun, is StartupDecision.AdoptPlain ->
                GateEvent.StartupLoaded(KeyMode.PLAIN, lockOn, failures, retryAt, UnlockRoute.Biometric)
            StartupDecision.KeyUnavailable -> GateEvent.StartupKeyFailed(lockOn)
        }
    }

    /**
     * E2. Owns [password] and zeroes it in every case. With [resetKey] it spends the key a fingerprint reset kept
     * (T55): taken for the open, or zeroed when this run ends before it, so no later E2 finds it (V16).
     */
    suspend fun unlockAndOpen(password: CharArray?, cipher: SlotCipher?, resetKey: Boolean): GateEvent {
        var resetKeyTaken = false
        try {
            when (maintenance.completeStartup()) {
                StartupCompletion.Transient -> return GateEvent.OpenFailed(OpenFailureKind.Transient)
                StartupCompletion.KeyUnavailable -> return GateEvent.KeyFailed
                StartupCompletion.Done -> Unit
            }
            val state = committedState() ?: return GateEvent.KeyFailed
            val obtained = when {
                resetKey -> {
                    resetKeyTaken = true
                    maintenance.takeResetKey(state.generation)?.let { Obtained.Key(it, kekBroken = false) }
                        ?: Obtained.Raise(passwordNeeded(PasswordReason.None)) // T22: the new password is asked
                }
                cipher != null -> keyFromSlot(cipher)
                else -> keyFromPassword(password, state)
            }
            return when (obtained) {
                is Obtained.Raise -> obtained.event
                is Obtained.Key -> {
                    if (obtained.kekBroken) lockController.reportKekBroken()
                    open(state.generation, obtained.rawKey, viaSlot = cipher != null)
                }
            }
        } finally {
            password?.fill('\u0000')
            if (resetKey && !resetKeyTaken) maintenance.takeResetKey(NO_GENERATION) // zeroes the kept key
        }
    }

    /** E3. */
    suspend fun closeDatabase() = holder.close()

    /** E4: checks [password] without touching the holder; owns and zeroes it. */
    suspend fun verifyPassword(password: CharArray): GateEvent {
        val bytes = passwordBytes(password) ?: ByteArray(0) // zeroes the chars
        try {
            return when (val result = verify(bytes)) {
                is UnlockResult.Verified -> {
                    lockController.clearKekBroken() // the password has just passed the KEK (DEC-86)
                    GateEvent.PasswordVerified
                }
                is UnlockResult.PasswordRejected -> GateEvent.PasswordRejected(result.failures, result.retryAtElapsedMs)
                is UnlockResult.Throttled -> GateEvent.PasswordRejected(result.failures, result.retryAtElapsedMs)
                // In Locked this means "could not be checked" (T50), never "the key cannot be obtained".
                else -> if (lockController.reportPasswordKekFailure()) GateEvent.KekFailedWithSlot else GateEvent.KeyFailed
            }
        } finally {
            bytes.fill(0)
            password.fill('\u0000')
        }
    }

    private suspend fun verify(bytes: ByteArray): UnlockResult = try {
        keyVault.verifyPassword(bytes)
    } catch (e: KeyVaultException) {
        UnlockResult.KeyFailed
    }

    private suspend fun keyFromPassword(password: CharArray?, state: KeyState): Obtained {
        // DEC-86 applies to the password path only: a password was passed and the slot is PASSWORD.
        val passwordPath = password != null && state.slot.mode == KeyMode.PASSWORD
        val bytes = password?.let { passwordBytes(it) ?: ByteArray(0) }
        val result = try {
            keyVault.unlock(bytes)
        } catch (e: KeyVaultException) {
            UnlockResult.KeyFailed
        } finally {
            bytes?.fill(0)
        }
        return when (result) {
            is UnlockResult.Key -> {
                // The password passed the KEK, whatever the open does next (DEC-86).
                if (passwordPath) lockController.clearKekBroken()
                Obtained.Key(result.rawKey, result.kekBroken)
            }
            is UnlockResult.PasswordRejected ->
                Obtained.Raise(GateEvent.PasswordRejected(result.failures, result.retryAtElapsedMs))
            is UnlockResult.Throttled -> Obtained.Raise(GateEvent.PasswordRejected(result.failures, result.retryAtElapsedMs))
            UnlockResult.PasswordNeeded -> Obtained.Raise(passwordNeeded(PasswordReason.None))
            else -> Obtained.Raise(
                if (passwordPath && lockController.reportPasswordKekFailure()) GateEvent.KekFailedWithSlot else GateEvent.KeyFailed,
            )
        }
    }

    /** A slot failure never counts and is never KeyUnavailable while the password can still open (DEC-60). */
    private suspend fun keyFromSlot(cipher: SlotCipher): Obtained {
        val result = try {
            keyVault.unlockWithBiometric(cipher)
        } catch (e: KeyVaultException) {
            UnlockResult.KeyFailed
        }
        return when (result) {
            is UnlockResult.Key -> Obtained.Key(result.rawKey, result.kekBroken)
            is UnlockResult.SlotFailed -> Obtained.Raise(
                if (result.invalidated) passwordNeeded(PasswordReason.FingerprintChanged) else GateEvent.SlotUnlockFailed,
            )
            else -> Obtained.Raise(GateEvent.KeyFailed)
        }
    }

    /** E2 step 4: the holder takes ownership of [rawKey]. */
    private suspend fun open(generation: Long, rawKey: ByteArray, viaSlot: Boolean): GateEvent =
        when (val result = holder.open(generation, rawKey)) {
            OpenResult.Opened -> GateEvent.Opened
            is OpenResult.Failed ->
                if (viaSlot && result.kind == OpenFailureKind.Corrupt && result.keyRejected) {
                    // A slot-released key is not proven correct, so this is never Corrupt (DEC-60, DEC-85).
                    keyVault.deleteBiometricKey()
                    passwordNeeded(PasswordReason.FingerprintChanged)
                } else {
                    GateEvent.OpenFailed(result.kind)
                }
        }

    /** The snapshot counters, so the password screen shows a running countdown at once. */
    private suspend fun passwordNeeded(reason: PasswordReason): GateEvent {
        val settings = settingsStore.snapshot()
        val failures = settings.unlockFailures
        return GateEvent.PasswordNeeded(failures, effectiveRetryAt(failures, settings.unlockRetryAtElapsedMs), reason)
    }

    /** A reboot since the failures must not leave a stale countdown (security.md § 5.3). */
    private fun effectiveRetryAt(failures: Int, stored: Long) =
        UnlockBackoff.effectiveRetryAt(failures, stored, appTime.elapsedMillis())

    private suspend fun committedState(): KeyState? = try {
        keyVault.readState()
    } catch (e: KeyVaultException) {
        null
    }

    private companion object {
        /** Matches no generation, so `takeResetKey` zeroes the key it kept and returns null. */
        const val NO_GENERATION = -1L
    }

    private sealed interface Obtained {
        class Key(val rawKey: ByteArray, val kekBroken: Boolean) : Obtained {
            override fun toString() = "Key"
        }

        class Raise(val event: GateEvent) : Obtained {
            override fun toString() = "Raise($event)"
        }
    }
}
