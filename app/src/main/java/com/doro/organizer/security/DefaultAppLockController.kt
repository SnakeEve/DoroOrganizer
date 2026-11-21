package com.doro.organizer.security

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockBackoff
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.settings.SettingsStore
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The production [AppLockController] (security.md § 7.3). It holds only the exemption, the prompt-in-flight and
 * auto-prompt flags, the credential purpose, the current [LockScreen], the last resolved route, the offer, the
 * kekBroken notice and the `kekBroken` route flag; every event goes to [listener] on the main thread. [listener] and
 * [slotWriter] are providers, resolved at use, because `SessionGate` and `DbMaintenance` depend on this class.
 * [texts] is resolved at each prompt, so a prompt follows a runtime language change. [sdkInt] picks the
 * device-credential branch (API 30+ prompt, API 28–29 intent, § 7.6). A failed settings read (an `IOException` of
 * DataStore) never crashes the background work started here: that run is skipped and the next ON_START or tap retries.
 */
class DefaultAppLockController(
    private val biometric: BiometricGateway,
    private val keyVault: KeyVault,
    private val slotWriter: () -> BiometricSlotWriter,
    private val externalActivities: ExternalActivities,
    private val settingsStore: SettingsStore,
    private val appTime: AppTime,
    processLifecycle: LifecycleOwner,
    private val listener: () -> LockListener,
    private val texts: () -> PromptTexts,
    private val dispatchers: AppDispatchers,
    private val applicationScope: CoroutineScope,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AppLockController {
    // Main-thread state.
    private var exemptUntil: Long? = null
    private var promptInFlight = false
    private var unlockRunning = false
    private var credentialRequest: ExternalRequest? = null
    private var credentialPurpose: AuthPurpose? = null
    private var credentialResult: CompletableDeferred<Int>? = null
    private val autoPrompt = MutableStateFlow(false)

    // Read off the main thread as well.
    @Volatile private var screen = LockScreen.Other
    @Volatile private var kekBroken = false
    private val route = MutableStateFlow<UnlockRoute?>(null)
    private val offer = MutableStateFlow<SlotOffer?>(null)
    private val notice = MutableStateFlow(false)

    override val lockRoute: StateFlow<UnlockRoute?> = route.asStateFlow()
    override val slotOffer: StateFlow<SlotOffer?> = offer.asStateFlow()
    override val kekNotice: StateFlow<Boolean> = notice.asStateFlow()

    init {
        // Observed from process start (security.md § 7.7); the container builds this object on the main thread.
        processLifecycle.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> onProcessStop()
                    Lifecycle.Event.ON_START -> onProcessStart()
                    else -> Unit
                }
            },
        )
    }

    // Activity

    override fun attach(activity: FragmentActivity) {
        biometric.attach(activity)
        observe(activity)
    }

    override fun detach(activity: FragmentActivity) {
        // The gateway answers a prompt that dies with a finishing activity, so `prompted` clears promptInFlight itself;
        // across a configuration change the prompt survives and reports to the new instance (§ 7.3).
        biometric.detach(activity)
    }

    /** The auto-prompt collector of security.md § 7.3, covering both orders of `Locked` and `ON_RESUME`. */
    internal fun observe(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) exemptUntil = null },
        )
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                autoPrompt.collect { armed ->
                    if (armed) {
                        autoPrompt.value = false
                        // Detached: a pause while the system prompt is shown must not cancel the wait for its result.
                        applicationScope.launch(dispatchers.main) { unlock() }
                    }
                }
            }
        }
    }

    // Process lifecycle and re-lock rules (§ 7.7)

    private fun onProcessStop() {
        if (exemptionActive()) return
        if (settingsStore.settings.value?.biometricLock == true) listener().onLockNow()
    }

    private fun onProcessStart() {
        val until = exemptUntil
        // An active exemption means an app-launched trip ends here; its result callback clears the exemption.
        if (until == null || appTime.elapsedMillis() > until) {
            if (until != null) {
                exemptUntil = null // the exempted stop was a real background trip
                if (settingsStore.settings.value?.biometricLock == true) listener().onLockNow()
            }
            if (screen.prompts) autoPrompt.value = true
        }
        // DEC-86: independent of the exemption, because it only offers a button.
        if (screen == LockScreen.Password && kekBroken) {
            applicationScope.launch(dispatchers.main) {
                orSkip("recheck") { unlockRoute() }?.let { listener().onFingerprintRechecked(it is UnlockRoute.Slot) }
            }
        }
    }

    private fun exemptionActive(): Boolean = exemptUntil?.let { appTime.elapsedMillis() <= it } == true

    override fun beforeLaunch(kind: ExternalKind) {
        exemptUntil = appTime.elapsedMillis() + EXEMPTION_MS
    }

    override fun afterResult(kind: ExternalKind) {
        exemptUntil = null
    }

    override fun notLaunched(request: ExternalRequest) {
        // A confirm-credential screen that never opened ends like one the user backed out of (§ 7.6).
        if (request === credentialRequest) onCredentialResult(Activity.RESULT_CANCELED)
    }

    override fun onCredentialResult(resultCode: Int) {
        val purpose = credentialPurpose
        credentialRequest = null
        credentialPurpose = null
        if (purpose == AuthPurpose.DisableLock) {
            credentialResult?.complete(resultCode)
            credentialResult = null
        } else if (resultCode == Activity.RESULT_OK) {
            listener().onUnlocked()
        } else {
            listener().onAuthError(LockError(BiometricPrompt.ERROR_USER_CANCELED, null)) // as USER_CANCELED (§ 7.6)
        }
    }

    // Gate screens

    override fun onGateScreenChanged(screen: LockScreen, showsError: Boolean) {
        val previous = this.screen
        this.screen = screen
        if (screen != LockScreen.Content) offer.value = null
        if (screen != previous) route.value = null
        // A lock entered because an unlock just failed waits for "Unlock" or the next ON_START (T44).
        autoPrompt.value = screen.prompts && !showsError
        if (screen.prompts) {
            applicationScope.launch(dispatchers.main) {
                val resolved = orSkip("route") { unlockRoute() }
                if (resolved != null && this@DefaultAppLockController.screen == screen) route.value = resolved
            }
        }
    }

    override fun prompt() {
        applicationScope.launch(dispatchers.main) { unlock() }
    }

    override fun usePassword() {
        applicationScope.launch(dispatchers.main) {
            if (route.value == UnlockRoute.Slot && screen.prompts) orSkip("use password") { passwordRequired(PasswordCause.UserChose) }
        }
    }

    override suspend fun unlockRoute(): UnlockRoute {
        val shown = screen
        val inputs = inputs()
        return publicRoute(decision(shown, inputs), inputs)
    }

    /** One unlock attempt on the current screen (security.md § 7.4). */
    private suspend fun unlock() {
        val shown = screen
        if (!shown.prompts || unlockRunning || promptInFlight) return
        unlockRunning = true
        try {
            orSkip("unlock") { unlockOn(shown) }
        } finally {
            unlockRunning = false
        }
    }

    private suspend fun unlockOn(shown: LockScreen) {
        val inputs = inputs()
        val decision = decision(shown, inputs)
        if (screen == shown) route.value = publicRoute(decision, inputs)
        when (decision) {
            // The key-state cannot be read over a key screen: fail closed with a line saying so; "Unlock" tries again
            // (§ 7.5, DEC-96).
            null -> listener().onAuthError(LockError(LockError.KEY_STATE_UNREADABLE, null))
            // PLAIN and DEVICE only: decision() never lets a PASSWORD-mode key screen unlock without a key check.
            PolicyDecision.None -> listener().onUnlocked()
            PolicyDecision.AutoOff -> autoOffAndUnlock()
            PolicyDecision.Biometric -> strongUnlock()
            PolicyDecision.DeviceCredential -> credentialUnlock()
            PolicyDecision.Slot -> slotUnlock(shown)
            is PolicyDecision.Password -> passwordRequired(decision.cause)
        }
    }

    private suspend fun strongUnlock() {
        when (val result = prompted(PromptRequest(texts().unlockTitle, false, texts().cancel))) {
            is PromptResult.Succeeded -> listener().onUnlocked()
            is PromptResult.Error -> when (result.code) {
                in PromptCodes.NO_BIOMETRIC -> credentialUnlock()
                else -> authError(result)
            }
        }
    }

    private suspend fun credentialUnlock() {
        if (sdkInt >= Build.VERSION_CODES.R) {
            when (val result = prompted(PromptRequest(texts().unlockTitle, credentialOnly = true, negativeText = null))) {
                is PromptResult.Succeeded -> listener().onUnlocked()
                is PromptResult.Error ->
                    if (result.code == PromptCodes.NO_DEVICE_CREDENTIAL) autoOffAndUnlock() else authError(result)
            }
        } else {
            val intent = biometric.credentialIntent(texts().unlockTitle) ?: return autoOffAndUnlock()
            requestCredential(AuthPurpose.Unlock, intent, result = null)
        }
    }

    private suspend fun slotUnlock(shown: LockScreen) {
        val cipher = when (val prepared = withContext(dispatchers.io) { keyVault.biometricUnlockCipher() }) {
            is SlotCipherResult.Ready -> prepared.slotCipher
            SlotCipherResult.NoSlot -> return passwordRequired(PasswordCause.SlotMissing)
            SlotCipherResult.Invalidated -> return passwordRequired(PasswordCause.SlotInvalidated)
            SlotCipherResult.Unavailable -> return passwordRequired(PasswordCause.SlotUnavailable)
        }
        when (val result = prompted(PromptRequest(texts().unlockTitle, false, texts().usePassword, cipher))) {
            is PromptResult.Succeeded -> when {
                // Never an unlock, and not counted toward the slot's repeated failures (DEC-79).
                !result.cryptoMatches -> listener().onAuthError(LockError(LockError.SLOT_FAILED, null))
                shown == LockScreen.PromptToOpen -> listener().onSlotUnlocked(cipher)
                else -> verifySlot(cipher)
            }
            is PromptResult.Error -> when (result.code) {
                PromptCodes.USE_PASSWORD -> passwordRequired(PasswordCause.UserChose)
                in PromptCodes.NO_BIOMETRIC -> passwordRequired(PasswordCause.NoBiometric)
                else -> authError(result)
            }
        }
    }

    /** Return from background in `PASSWORD` mode: the authorized cipher is only proof, the database is open. */
    private suspend fun verifySlot(cipher: SlotCipher) {
        when (val verified = withContext(dispatchers.io) { keyVault.verifyBiometric(cipher) }) {
            is UnlockResult.Verified -> {
                if (verified.kekBroken) reportKekBroken()
                listener().onUnlocked()
            }
            UnlockResult.SlotFailed(invalidated = true) -> passwordRequired(PasswordCause.SlotInvalidated)
            else -> listener().onAuthError(LockError(LockError.SLOT_FAILED, null))
        }
    }

    private fun authError(result: PromptResult.Error) {
        val silent = result.code in PromptCodes.CANCEL
        listener().onAuthError(LockError(result.code, if (silent) null else result.message))
    }

    private suspend fun autoOffAndUnlock() {
        writeAutoOff()
        listener().onUnlocked()
    }

    private suspend fun writeAutoOff() {
        try {
            withContext(dispatchers.io) { settingsStore.setLockAutoOff() }
        } catch (e: IOException) {
            AppLog.d("lock: auto-off write failed: ${e.javaClass.simpleName}") // the next unlock tries again
        }
    }

    private suspend fun passwordRequired(cause: PasswordCause) {
        val password = UnlockPolicy.toRoute(PolicyDecision.Password(cause), kekFailedWithLiveSlot()) as UnlockRoute.Password
        val (failures, retryAt) = counters()
        listener().onPasswordRequired(password.reason, password.fingerprintUsable, failures, retryAt)
    }

    // Settings-facing members (§ 7.1, § 7.3)

    override suspend fun availability(): LockAvailability {
        val inputs = inputs()
        val slot = when {
            inputs.mode != KeyMode.PASSWORD || !inputs.lockOn -> SlotState.NotUsed
            inputs.bio == BioSlotStatus.Live -> SlotState.Ready
            inputs.bio == BioSlotStatus.Dead -> SlotState.Invalidated
            inputs.bio == BioSlotStatus.Unknown -> SlotState.Unknown
            else -> SlotState.Missing
        }
        return LockAvailability(inputs.biometric, inputs.deviceSecure, slot)
    }

    override suspend fun confirm(purpose: AuthPurpose): AppResult<ToggleOutcome> {
        require(purpose == AuthPurpose.EnableLock || purpose == AuthPurpose.DisableLock) { "not a toggle purpose" }
        val inputs = inputs()
        if (inputs.mode == KeyMode.PASSWORD) {
            return if (purpose == AuthPurpose.EnableLock) AppResult.Ok(ToggleOutcome.PasswordRequired) else disableWithSlot(inputs)
        }
        return if (purpose == AuthPurpose.EnableLock) enableWithoutSlot(inputs) else disableWithoutSlot(inputs)
    }

    private suspend fun enableWithoutSlot(inputs: LockInputs): AppResult<ToggleOutcome> {
        if (!inputs.usable) return AppResult.Err(AppError.AuthFailed)
        // Strong only, never the credential: also the test prompt of STATUS_UNKNOWN.
        return when (val result = prompted(PromptRequest(texts().enableTitle, false, texts().cancel))) {
            is PromptResult.Succeeded -> writeLock(true)?.let { AppResult.Err(it) } ?: AppResult.Ok(ToggleOutcome.Done)
            is PromptResult.Error -> cancelledOr(result) { AppResult.Err(AppError.AuthFailed) }
        }
    }

    private suspend fun disableWithoutSlot(inputs: LockInputs): AppResult<ToggleOutcome> =
        when (UnlockPolicy.route(KeyMode.PLAIN, false, true, inputs.deviceSecure, inputs.biometric, BioSlotStatus.None, false)) {
            PolicyDecision.Biometric -> when (val result = prompted(PromptRequest(texts().disableTitle, false, texts().cancel))) {
                is PromptResult.Succeeded -> lockOff()
                is PromptResult.Error -> when (result.code) {
                    in PromptCodes.CANCEL -> AppResult.Ok(ToggleOutcome.Cancelled)
                    in PromptCodes.NO_BIOMETRIC -> disableWithCredential()
                    else -> AppResult.Err(AppError.AuthFailed)
                }
            }
            PolicyDecision.DeviceCredential -> disableWithCredential()
            else -> lockOff() // no secure lock screen: nothing to confirm with (§ 7.1)
        }

    private suspend fun disableWithCredential(): AppResult<ToggleOutcome> {
        if (sdkInt >= Build.VERSION_CODES.R) {
            return when (val result = prompted(PromptRequest(texts().disableTitle, credentialOnly = true, negativeText = null))) {
                is PromptResult.Succeeded -> lockOff()
                is PromptResult.Error -> when (result.code) {
                    PromptCodes.NO_DEVICE_CREDENTIAL -> lockOff()
                    in PromptCodes.CANCEL -> AppResult.Ok(ToggleOutcome.Cancelled)
                    else -> AppResult.Err(AppError.AuthFailed)
                }
            }
        }
        val intent = biometric.credentialIntent(texts().disableTitle) ?: return lockOff()
        val result = CompletableDeferred<Int>()
        requestCredential(AuthPurpose.DisableLock, intent, result)
        return if (result.await() == Activity.RESULT_OK) lockOff() else AppResult.Ok(ToggleOutcome.Cancelled)
    }

    /**
     * The API 28–29 confirm-credential screen. A request still pending is superseded and ends as a cancel; every request
     * ends in [onCredentialResult], directly or through [notLaunched].
     */
    private fun requestCredential(purpose: AuthPurpose, intent: Intent, result: CompletableDeferred<Int>?) {
        if (credentialRequest != null) onCredentialResult(Activity.RESULT_CANCELED)
        val request = ExternalRequest(ExternalKind.CredentialConfirm, intent)
        credentialRequest = request
        credentialPurpose = purpose
        credentialResult = result
        externalActivities.request(request)
    }

    /** `PASSWORD` mode: the slot prompt and `verifyBiometric`, or the password dialog (§ 7.3). */
    private suspend fun disableWithSlot(inputs: LockInputs): AppResult<ToggleOutcome> {
        if (!inputs.liveFingerprint) return AppResult.Ok(ToggleOutcome.PasswordRequired)
        val cipher = (withContext(dispatchers.io) { keyVault.biometricUnlockCipher() } as? SlotCipherResult.Ready)
            ?.slotCipher ?: return AppResult.Ok(ToggleOutcome.PasswordRequired)
        return when (val result = prompted(PromptRequest(texts().disableTitle, false, texts().usePassword, cipher))) {
            is PromptResult.Succeeded -> {
                if (!result.cryptoMatches) return AppResult.Err(AppError.AuthFailed)
                when (val verified = withContext(dispatchers.io) { keyVault.verifyBiometric(cipher) }) {
                    is UnlockResult.Verified -> {
                        if (verified.kekBroken) reportKekBroken()
                        lockOffAndRemoveSlot()
                    }
                    is UnlockResult.SlotFailed -> AppResult.Ok(ToggleOutcome.PasswordRequired)
                    else -> AppResult.Err(AppError.AuthFailed)
                }
            }
            is PromptResult.Error -> when (result.code) {
                PromptCodes.USE_PASSWORD, in PromptCodes.NO_BIOMETRIC -> AppResult.Ok(ToggleOutcome.PasswordRequired)
                in PromptCodes.CANCEL -> AppResult.Ok(ToggleOutcome.Cancelled)
                else -> AppResult.Err(AppError.AuthFailed)
            }
        }
    }

    override suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome> {
        try {
            require(purpose == AuthPurpose.EnableLock || purpose == AuthPurpose.DisableLock || purpose == AuthPurpose.SetUpSlot) {
                "not a password-confirmed purpose"
            }
            require(withContext(dispatchers.io) { committedMode() } == KeyMode.PASSWORD) { "PASSWORD mode only" }
            verifyPassword(password)?.let { return AppResult.Err(it) }
        } finally {
            password.fill('\u0000')
        }
        clearKekBroken() // the password has just passed the KEK (DEC-86)
        if (purpose == AuthPurpose.DisableLock) return lockOffAndRemoveSlot()
        val title = if (purpose == AuthPurpose.EnableLock) texts().enableTitle else texts().slotTitle
        val cipher = (withContext(dispatchers.io) { keyVault.biometricArmCipher() } as? SlotCipherResult.Ready)
            ?.slotCipher ?: return AppResult.Err(AppError.AuthFailed)
        when (val result = prompted(PromptRequest(title, false, texts().cancel, cipher))) {
            is PromptResult.Error -> return cancelledOr(result) { AppResult.Err(AppError.AuthFailed) }
            is PromptResult.Succeeded -> if (!result.cryptoMatches) return AppResult.Err(AppError.AuthFailed)
        }
        if (purpose == AuthPurpose.SetUpSlot) return slotWriter().createBiometricSlot(cipher).map { ToggleOutcome.Done }
        writeLock(true)?.let { return AppResult.Err(it) } // the setting first (DEC-65)
        val created = slotWriter().createBiometricSlot(cipher)
        // A failed write-back leaves the lock on without a slot, which E5's re-creation offer repairs (§ 7.8).
        if (created is AppResult.Err) writeLock(false)
        return created.map { ToggleOutcome.Done }
    }

    /** `verifyPassword` for the Settings dialogs; null when it was correct (§ 7.1 step 1). */
    private suspend fun verifyPassword(password: CharArray): AppError? = withContext(dispatchers.io) {
        val bytes = passwordBytes(password) ?: ByteArray(0)
        try {
            when (val result = keyVault.verifyPassword(bytes)) {
                is UnlockResult.Verified -> null
                is UnlockResult.PasswordRejected -> AppError.WrongPassword
                is UnlockResult.Throttled -> AppError.Throttled(result.retryAtElapsedMs)
                is UnlockResult.StorageFull -> AppError.StorageFull // not checked: not counted (DEC-75)
                else -> AppError.KeyUnavailable // a message only; the gate is not touched (DEC-86)
            }
        } catch (e: KeyVaultException) {
            AppError.KeyUnavailable
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun lockOff(): AppResult<ToggleOutcome> =
        writeLock(false)?.let { AppResult.Err(it) } ?: AppResult.Ok(ToggleOutcome.Done)

    /** The setting first, then the slot (DEC-65); a crash in between leaves a stale slot for `reconcileLock()`. */
    private suspend fun lockOffAndRemoveSlot(): AppResult<ToggleOutcome> {
        lockOff().let { if (it is AppResult.Err) return it }
        slotWriter().removeBiometricSlot()
        return AppResult.Ok(ToggleOutcome.Done)
    }

    /** `security.app_lock`; null when written. A failed DataStore write is a full disk (`SettingsStore`). */
    private suspend fun writeLock(on: Boolean): AppError? = try {
        withContext(dispatchers.io) { settingsStore.setBiometricLock(on) }
        null
    } catch (e: IOException) {
        AppError.StorageFull
    }

    override fun enroll() {
        val (intent, fallback) = biometric.enrollIntents()
        externalActivities.request(ExternalRequest(ExternalKind.Enroll, intent, fallbackIntent = fallback))
    }

    override suspend fun fingerprintUnlockAvailable(): FingerprintAvailability {
        val inputs = inputs()
        return when {
            inputs.liveFingerprint -> FingerprintAvailability.Available
            inputs.unknownFingerprint -> FingerprintAvailability.Unknown
            else -> FingerprintAvailability.Unavailable
        }
    }

    override suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?> {
        require(purpose == AuthPurpose.ResetPassword || purpose == AuthPurpose.RestoreKey) { "not a slot purpose" }
        val restore = purpose == AuthPurpose.RestoreKey
        val inputs = inputs()
        // RestoreKey prompts only where the route would be Slot; the kekBroken flag makes the reminder not due (DEC-86).
        val reminderBlocks = restore && inputs.reminderDue && !kekBroken
        if (!inputs.liveFingerprint || reminderBlocks) return AppResult.Ok(null)
        val cipher = (withContext(dispatchers.io) { keyVault.biometricUnlockCipher() } as? SlotCipherResult.Ready)
            ?.slotCipher ?: return AppResult.Ok(null)
        val request = if (restore) {
            PromptRequest(texts().unlockTitle, false, texts().usePassword, cipher)
        } else {
            PromptRequest(texts().resetTitle, false, texts().cancel, cipher)
        }
        val result = prompted(request)
        return when {
            result is PromptResult.Succeeded && result.cryptoMatches -> AppResult.Ok(cipher)
            restore -> AppResult.Ok(null) // every failure falls back to the password (DEC-84)
            result is PromptResult.Error && result.code in PromptCodes.CANCEL -> AppResult.Ok(null)
            else -> AppResult.Err(AppError.AuthFailed)
        }
    }

    override suspend fun offerSlotSetup() {
        reconcileLock()
        val inputs = orSkip("offer") { inputs() } ?: return
        if (inputs.mode != KeyMode.PASSWORD || !inputs.lockOn || !inputs.usable) return
        val due = when (inputs.bio) {
            BioSlotStatus.None -> SlotOffer.Missing
            BioSlotStatus.Dead -> SlotOffer.Invalidated
            BioSlotStatus.Live, BioSlotStatus.Unknown -> return // Unknown: the slot may still be good (DEC-77)
        }
        if (screen == LockScreen.Content) offer.value = due // the offer exists only in this foreground session
    }

    override suspend fun createSlot(): AppResult<Boolean> {
        if (offer.value == null) return AppResult.Ok(false)
        try {
            val cipher = (withContext(dispatchers.io) { keyVault.biometricArmCipher() } as? SlotCipherResult.Ready)
                ?.slotCipher ?: return AppResult.Err(AppError.AuthFailed)
            return when (val result = prompted(PromptRequest(texts().slotTitle, false, texts().cancel, cipher))) {
                is PromptResult.Succeeded ->
                    if (result.cryptoMatches) slotWriter().createBiometricSlot(cipher).map { true } else AppResult.Err(AppError.AuthFailed)
                is PromptResult.Error ->
                    if (result.code in PromptCodes.CANCEL) AppResult.Ok(false) else AppResult.Err(AppError.AuthFailed)
            }
        } finally {
            offer.value = null
        }
    }

    override fun dismissSlotOffer() {
        offer.value = null
    }

    override suspend fun reconcileLock() {
        try {
            val lockOn = withContext(dispatchers.io) { settingsStore.snapshot().biometricLock }
            if (!lockOn) {
                // A slot left behind while the lock is off is stale (§ 3.7.5). One whose key cannot be looked up waits
                // for a later reconciliation: nothing is deleted on an unknown answer (DEC-77).
                val status = withContext(dispatchers.io) { keyVault.biometricSlotStatus() }
                if (status != BioSlotStatus.None && status != BioSlotStatus.Unknown) {
                    slotWriter().removeBiometricSlot()
                }
            } else if (withContext(dispatchers.io) { committedMode() } == KeyMode.PASSWORD && !biometric.isDeviceSecure()) {
                // The PASSWORD-mode auto-off runs only after a verified password (§ 7.6): setting first, then the slot.
                withContext(dispatchers.io) { settingsStore.setLockAutoOff() }
                slotWriter().removeBiometricSlot()
            }
        } catch (e: IOException) {
            AppLog.d("lock: reconcile failed: ${e.javaClass.simpleName}")
        }
    }

    // kekBroken (DEC-86)

    override fun reportKekBroken() {
        kekBroken = true
        notice.value = true
    }

    override suspend fun reportPasswordKekFailure(): Boolean {
        val live = orSkip("kek report") {
            withContext(dispatchers.io) {
                committedMode() == KeyMode.PASSWORD && settingsStore.snapshot().biometricLock &&
                    keyVault.biometricSlotStatus() == BioSlotStatus.Live
            }
        } == true
        if (live) reportKekBroken()
        return live
    }

    override fun dismissKekNotice() {
        notice.value = false
    }

    override fun clearKekBroken() {
        kekBroken = false
        notice.value = false
    }

    // Shared helpers

    /**
     * One prompt at a time: a request while another prompt is shown is answered as a system cancel. The gateway answers
     * every request exactly once, so promptInFlight always clears; a cancelled caller dismisses its prompt.
     */
    private suspend fun prompted(request: PromptRequest): PromptResult = withContext(dispatchers.main) {
        if (promptInFlight) return@withContext PromptResult.Error(PromptCodes.BUSY, "")
        promptInFlight = true
        try {
            suspendCancellableCoroutine<PromptResult> { continuation ->
                biometric.authenticate(request) { continuation.resume(it) }
            }
        } catch (e: CancellationException) {
            biometric.cancel()
            throw e
        } finally {
            promptInFlight = false
        }
    }

    private inline fun cancelledOr(
        result: PromptResult.Error,
        otherwise: () -> AppResult<ToggleOutcome>,
    ): AppResult<ToggleOutcome> = if (result.code in PromptCodes.CANCEL) AppResult.Ok(ToggleOutcome.Cancelled) else otherwise()

    /**
     * The decision for [shown]. Only a `PromptWithoutKey` screen takes the route without a key (§ 5.1); on any other
     * screen an unknown mode gives null, and a `PASSWORD`-mode lock turned off meanwhile asks the password, because
     * there only a password or the slot unlocks (§ 7.5, architecture.md § Invariants 7).
     */
    private fun decision(shown: LockScreen, inputs: LockInputs): PolicyDecision? {
        if (shown == LockScreen.PromptWithoutKey) {
            return UnlockPolicy.routeOverKeyUnavailable(inputs.lockOn, inputs.deviceSecure, inputs.biometric)
        }
        val mode = inputs.mode ?: return null
        val routed = UnlockPolicy.route(
            mode, coldStart = shown != LockScreen.Prompt, inputs.lockOn, inputs.deviceSecure, inputs.biometric,
            inputs.bio, reminderDue = inputs.reminderDue && !kekBroken,
        )
        return if (routed == PolicyDecision.None && mode == KeyMode.PASSWORD) PolicyDecision.Password(PasswordCause.LockOff) else routed
    }

    /** An unknown mode shows no "Use password": the Biometric route. */
    private fun publicRoute(decision: PolicyDecision?, inputs: LockInputs): UnlockRoute = UnlockPolicy.toRoute(
        decision ?: PolicyDecision.Biometric,
        kekFailedWithLiveSlot = kekBroken && inputs.bio == BioSlotStatus.Live,
    )

    private suspend fun kekFailedWithLiveSlot(): Boolean =
        kekBroken && withContext(dispatchers.io) { keyVault.biometricSlotStatus() } == BioSlotStatus.Live

    private suspend fun inputs(): LockInputs = withContext(dispatchers.io) {
        val mode = committedMode()
        val lockOn = settingsStore.snapshot().biometricLock
        val passwordMode = mode == KeyMode.PASSWORD
        val partial = LockInputs(
            mode, lockOn, biometric.isDeviceSecure(), biometric.canAuthenticate(),
            bio = if (passwordMode) keyVault.biometricSlotStatus() else BioSlotStatus.None,
            reminderDue = false,
        )
        if (partial.liveFingerprint) partial.copy(reminderDue = keyVault.isPasswordReminderDue()) else partial
    }

    private suspend fun committedMode(): KeyMode? = try {
        keyVault.readState()?.slot?.mode
    } catch (e: KeyVaultException) {
        null
    }

    private suspend fun counters(): Pair<Int, Long> {
        val settings = withContext(dispatchers.io) { settingsStore.snapshot() }
        val failures = settings.unlockFailures
        return failures to UnlockBackoff.effectiveRetryAt(failures, settings.unlockRetryAtElapsedMs, appTime.elapsedMillis())
    }

    /** [block], or null after logging the class name of a failed settings read (DEC-70). */
    private inline fun <T> orSkip(what: String, block: () -> T): T? = try {
        block()
    } catch (e: IOException) {
        AppLog.d("lock: $what skipped: ${e.javaClass.simpleName}")
        null
    }

    private inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
        is AppResult.Ok -> AppResult.Ok(transform(value))
        is AppResult.Err -> this
    }

    private companion object {
        /** The re-lock exemption of an app-launched external activity (security.md § 7.7). */
        const val EXEMPTION_MS = 600_000L
    }
}
