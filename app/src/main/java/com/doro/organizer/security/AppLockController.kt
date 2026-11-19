package com.doro.organizer.security

import androidx.fragment.app.FragmentActivity
import com.doro.organizer.core.external.ExternalCallbacks
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.UnlockRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * The biometric app lock (DEC-65, security.md § 7.3): the only component that starts an authentication prompt. It
 * raises gate events through `LockListener`; whether the app is locked is `SessionGate`'s state, never its own.
 */
interface AppLockController : ExternalCallbacks {
    /** `MainActivity.onCreate`, every instance: one `BiometricPrompt` and the auto-prompt collector. */
    fun attach(activity: FragmentActivity)

    /** `MainActivity.onDestroy`. */
    fun detach(activity: FragmentActivity)

    /** The Settings row (security.md § 7.1). */
    suspend fun availability(): LockAvailability

    /** The route for the current screen through `UnlockPolicy` with live inputs; never prompts, no Keystore operation. */
    suspend fun unlockRoute(): UnlockRoute

    /** The route of the lock screen currently shown; the lock screen shows "Use password" while it is `Slot`. */
    val lockRoute: StateFlow<UnlockRoute?>

    /** Called by `SessionGate` after every change of the shown screen; arms the one-shot auto-prompt. */
    fun onGateScreenChanged(screen: LockScreen, showsError: Boolean)

    /** The lock screen's "Unlock" button. */
    fun prompt()

    /** The lock screen's "Use password" button: on the `Slot` route only. */
    fun usePassword()

    /** `EnableLock` / `DisableLock` for the Settings toggle. */
    suspend fun confirm(purpose: AuthPurpose): AppResult<ToggleOutcome>

    /** `PASSWORD` mode: `EnableLock`, `DisableLock`, `SetUpSlot`. Owns and zeroes [password]. */
    suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome>

    /** `PASSWORD` mode, lock on, usable strong biometric, slot `Live`; the reminder does not affect it. */
    suspend fun fingerprintUnlockAvailable(): Boolean

    /** An authorized DECRYPT cipher for `ResetPassword` or `RestoreKey` (security.md § 7.3 results). */
    suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?>

    /** Non-null while the re-creation offer is due (security.md § 7.8); never persisted. */
    val slotOffer: StateFlow<SlotOffer?>

    /** Effect E5: `reconcileLock()`, then the offer when § 7.8 holds. */
    suspend fun offerSlotSetup()

    /** The offer's "Set up": `Ok(true)` created, `Ok(false)` cancelled or no offer pending. */
    suspend fun createSlot(): AppResult<Boolean>

    fun dismissSlotOffer()

    /** Removes a stale slot while the lock is off and performs the `PASSWORD`-mode auto-off (security.md § 3.7.5). */
    suspend fun reconcileLock()

    /** The kekBroken notice (DEC-86), in memory only. */
    val kekNotice: StateFlow<Boolean>

    fun reportKekBroken()

    /** After a Keystore-layer failure of the password slot: true when a live slot can replace the password. */
    suspend fun reportPasswordKekFailure(): Boolean

    fun dismissKekNotice()

    /** Clears the notice and the `kekBroken` route flag (DEC-86). */
    fun clearKekBroken()
}
