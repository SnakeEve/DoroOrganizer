package com.doro.organizer.testing

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.MainActivity
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.AndroidBioKeystore
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.FingerprintAvailability
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.ToggleOutcome
import com.doro.organizer.session.GateEvent
import com.doro.organizer.session.GateState
import com.doro.organizer.ui.NewNoteMarker
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals

/**
 * A cold start of the real app in the test process (testing.md § 7, Wiring) over the test's own files: [plant] writes
 * the key-state, the data and the settings through a [MaintenanceSetup] on the same files, vault directory and
 * settings; [coldStart] then starts the gate of [app]'s container and launches `MainActivity`, whose
 * `AppLockController.attach` runs the auto-prompt. Everything below the platform is real except [gateway] (the
 * fingerprint sensor), [settings], [time], [process] (unless [realProcessLifecycle]) and the idle-unless-targeted
 * [faultHook]; every announcement is recorded in [announcements]. With [realBiometrics] the container keeps the
 * production `BiometricGateway` and the vault the production key spec, so every slot prompt needs a touch on the
 * sensor and [gateway] sees nothing (testing.md § 2.1). Declare [app] as `@get:Rule(order = 3)` after [databases],
 * [vaults] and [scope].
 */
class GateScene(
    private val databases: TestDatabases,
    private val vaults: KeyVaultRule,
    private val scope: ApplicationScopeRule,
    private val realProcessLifecycle: Boolean = false,
    private val realBiometrics: Boolean = false,
) {
    val time = FakeAppTime()
    val settings = FakeSettingsStore().apply { load() }
    val gateway = FakeBiometricGateway()
    val process = TestProcessLifecycle()
    val kdf = TestKdf(time)
    val kek = FaultyKekKeystore()
    val bio = FaultyBioKeystore(if (realBiometrics) AndroidBioKeystore() else testBioKeystore())

    /** Blocks or fails the app's maintenance operations at a chosen step; idle until a test targets one. */
    val faultHook = ScriptedFaultHook()

    /** Every secret the app's vault allocated, to check their zeroing (L-03). */
    val secrets = SecretRecorder()

    val announcements = Announcements()

    /** Wraps the app's vault, for the cases that script a vault answer. */
    var wrapVault: (KeyVault) -> KeyVault = { it }

    /**
     * The list-position tests' probes (testing.md I-58, I-63): the repositories' wrappers, the marker's wrapper and
     * the dispatchers; null keeps the production member. Set them before the rules run, in the test's constructor.
     */
    var wrapNoteRepository: ((NoteRepository) -> NoteRepository)? = null
    var wrapEntityRepository: ((EntityRepository) -> EntityRepository)? = null
    var wrapNewNoteMarker: ((NewNoteMarker) -> NewNoteMarker)? = null
    var dispatchers: AppDispatchers? = null

    private lateinit var lock: CountingLockController

    /** The API level the controller decides its credential branch by; null: the phone's. Set before [coldStart]. */
    var controllerSdkInt: Int? = null

    val app = DoroAppRule(autoStart = false) {
        appTime = time
        settingsStore = settings
        processLifecycle = process.takeUnless { realProcessLifecycle }
        biometricGateway = gateway.takeUnless { realBiometrics }
        faultHook = this@GateScene.faultHook
        files = maintenanceFiles(databases, vaults)
        keyVault = { store, _, dispatchers ->
            wrapVault(vaults.vault(store, time, dispatchers, kek, newSecret = secrets, kdf = kdf, bio = bio))
        }
        wrapLockController = { CountingLockController(it).also { lock = it } }
        lockSdkInt = { controllerSdkInt }
        wrapAnnouncer = announcements::wrap
        wrapNoteRepository = this@GateScene.wrapNoteRepository
        wrapEntityRepository = this@GateScene.wrapEntityRepository
        wrapNewNoteMarker = this@GateScene.wrapNewNoteMarker
        dispatchers = this@GateScene.dispatchers
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    val gate get() = app.container.sessionGate

    /**
     * Data in [mode] (with [password] for `PASSWORD`), the lock setting and, with [slot], a live biometric slot; with
     * [sentinel], a note and a person that hold it (L-21); with [rows], whatever it writes.
     */
    suspend fun plant(
        mode: KeyMode,
        password: String? = null,
        lock: Boolean = false,
        slot: Boolean = false,
        sentinel: String? = null,
        rows: (TestRows.() -> Unit)? = null,
    ) {
        val setup = MaintenanceSetup(databases, vaults, scope.scope, settings, time, kek, bio)
        setup.start()
        if (mode != KeyMode.PLAIN) setup.changeKey(mode, null, password)
        if (slot) assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        sentinel?.let { text ->
            val sentinelRows = TestRows(setup.holder.openDatabase().openHelper.writableDatabase)
            val note = sentinelRows.note(content = text, createdAt = time.nowMillis())
            sentinelRows.linkPerson(note, sentinelRows.person(text, createdAt = time.nowMillis()))
        }
        rows?.let { write -> TestRows(setup.holder.openDatabase().openHelper.writableDatabase).write() }
        settings.setBiometricLock(lock)
        setup.holder.close()
    }

    /**
     * The process comes to the foreground, the gate starts and `MainActivity` is launched while the gate is still
     * `Starting`. Not guarded by [checkNotStarting]: the real gate leaves `Starting` by itself once startup has run,
     * and `ActivityScenario` waits until then for the first frame, which the splash screen holds back. A test that
     * holds startup back (a [faultHook] target on the way to the first state) must not cold-start, or the launch never
     * returns.
     */
    fun coldStart() {
        onMain { process.moveTo(Lifecycle.State.RESUMED) }
        app.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * Closes `MainActivity`, if launched, and forgets it, so [launchActivity] may launch it again; the container, the
     * gate and its process lifecycle stay. The `@After` of [GateTest] calls it; a test that plays an activity-hosted
     * part itself (the maintenance status host) calls it too, so that no second host answers.
     */
    fun closeActivity() {
        scenario?.close()
        scenario = null
    }

    /**
     * Launches `MainActivity` again over the running gate, after [closeActivity]; unlike [coldStart] the gate is not
     * started. Refused while the gate is `Starting` ([checkNotStarting]): await the state it moves on to first.
     */
    fun launchActivity() {
        check(scenario == null) { "MainActivity is already launched" }
        checkNotStarting(gate.state.value, "launch")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** A trip to the background and back through the fake process lifecycle (security.md § 7.7). */
    fun backgroundTrip() = onMain {
        check(!realProcessLifecycle) { "the real process lifecycle moves with the activities" }
        process.moveTo(Lifecycle.State.CREATED)
        process.moveTo(Lifecycle.State.RESUMED)
    }

    suspend fun awaitGate(predicate: (GateState) -> Boolean): GateState =
        withTimeout(TIMEOUT_MS) { gate.state.first(predicate) }

    suspend fun awaitGate(state: GateState): GateState = awaitGate { it == state }

    /**
     * Waits until effect E5 (`reconcileLock()`, then the offer) has finished [count] times in all, so a test asserts
     * its outcome, or the absence of an offer, after it ran.
     */
    suspend fun awaitOfferSetups(count: Int) = withTimeout(TIMEOUT_MS) { lock.offerSetups.first { it >= count } }

    /** The slot offers (E5) finished so far. */
    val offerSetups: Int get() = lock.offerSetups.value

    /** The calls of `fingerprintUnlockAvailable()` so far (the Forgot password screen asks it, ui.md § 10.3). */
    val availabilityCalls: Int get() = lock.availabilityCalls.get()

    /** Makes `fingerprintUnlockAvailable()` wait until [releaseAvailability], so a test sees the screen before it. */
    fun holdAvailability() = lock.holdAvailability()

    /** Waits until [count] `confirmWithPassword` calls have returned; their results in order. */
    suspend fun awaitPasswordConfirmations(count: Int): List<AppResult<ToggleOutcome>> =
        withTimeout(TIMEOUT_MS) { lock.passwordResults.first { it.size >= count } }

    fun releaseAvailability() = lock.releaseAvailability()

    fun dispatch(event: GateEvent) = onMain { gate.dispatch(event) }

    fun submit(password: String) = dispatch(GateEvent.PasswordSubmitted(password.toCharArray()))

    /** The live Room instance's holder state, compared by identity across a lock (same instance, no reopen). */
    fun holderState(): HolderState = app.container.databaseHolder.state.value

    /** A process restart: `MainActivity` closes and a new container takes over the same files; [coldStart] follows. */
    fun restart() {
        closeActivity()
        app.restart()
    }

    /** Runs [block] on the main thread with the launched `MainActivity`. */
    fun onActivity(block: (MainActivity) -> Unit) {
        checkNotNull(scenario) { "not started" }.onActivity(block)
    }

    /**
     * Recreates the launched `MainActivity` as a rotation does: its state saved, the ViewModelStore kept. Refused while
     * the gate is `Starting` ([checkNotStarting]): await the state it moves on to first.
     */
    fun recreateActivity() {
        recreatable().recreate()
    }

    /**
     * Recreates the launched `MainActivity` as after Android closed the app in the background: its state saved, every
     * ViewModel gone, so each is rebuilt from that state; the container (the gate) stays, as startup would restore it.
     * Refused while the gate is `Starting` ([checkNotStarting]).
     */
    fun recreateAfterProcessDeath() {
        recreatable().recreateAfterProcessDeath()
    }

    private fun recreatable(): ActivityScenario<MainActivity> = checkRecreatable(scenario, gate.state.value)

    /**
     * Moves the launched `MainActivity` to [state]. For CREATED (stopped) `ActivityScenario` returns at `onStop`,
     * before `onSaveInstanceState` has run, so a test that reads the saved state goes through [captureSavedState].
     */
    fun moveActivityTo(state: Lifecycle.State) {
        checkNotNull(scenario) { "not started" }.moveToState(state)
    }

    /** The main file of the committed generation. */
    suspend fun liveFile(): File = app.container.files.live(checkNotNull(app.container.keyVault.readState()).generation)

    /**
     * The runtime-corruption technique of testing.md § 5 for a `PLAIN` database open in the holder: with the WAL
     * empty, random bytes over every page but the first, so the next read on a reader connection fails.
     */
    suspend fun damageOpenPlainDatabase() {
        val file = liveFile()
        val db = (holderState() as HolderState.Open).db
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        (2..pageCount(file)).forEach { corruptPage(file, it) }
    }

    companion object {
        const val TIMEOUT_MS = 20_000L

        fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}

/**
 * The real controller, counting each finished E5 (`offerSlotSetup`), which reports through no flow, and each
 * availability question, whose answer it can hold back, and recording each result of `confirmWithPassword`, which
 * returns only after the controller has written everything it writes.
 */
private class CountingLockController(private val inner: AppLockController) : AppLockController by inner {
    private val finished = MutableStateFlow(0)
    val offerSetups: StateFlow<Int> = finished.asStateFlow()
    val availabilityCalls = AtomicInteger()
    private val held = MutableStateFlow(false)

    fun holdAvailability() {
        held.value = true
    }

    fun releaseAvailability() {
        held.value = false
    }

    override suspend fun fingerprintUnlockAvailable(): FingerprintAvailability {
        availabilityCalls.incrementAndGet()
        held.first { !it }
        return inner.fingerprintUnlockAvailable()
    }

    private val passwordAnswers = MutableStateFlow<List<AppResult<ToggleOutcome>>>(emptyList())
    val passwordResults: StateFlow<List<AppResult<ToggleOutcome>>> = passwordAnswers.asStateFlow()

    override suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome> =
        inner.confirmWithPassword(purpose, password).also { result -> passwordAnswers.update { it + result } }

    override suspend fun offerSlotSetup() {
        try {
            inner.offerSlotSetup()
        } finally {
            finished.update { it + 1 }
        }
    }
}
