package com.doro.organizer.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.DefaultDatabaseHolder
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.maintenance.DefaultDbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.data.maintenance.MaintenanceListener
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.data.maintenance.StartupCompletion
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.DefaultKeyVault
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.SlotCipherResult
import com.doro.organizer.security.UnlockResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals

/** Records what DbMaintenance reports to the gate, in order (db-lifecycle.md § Interface and status). */
class RecordingMaintenanceListener : MaintenanceListener {
    private val events = mutableListOf<Any>()

    /** Runs inside every `onMaintenanceStarted`, after recording it: what a test sees when the gate enters Maintenance. */
    @Volatile
    var onStarted: ((MaintenanceOp) -> Unit)? = null

    /** Thrown once by the next `onMaintenanceFinished`, after recording it: a gate bug. */
    @Volatile
    var failNextFinished: Throwable? = null

    /** `(op, fingerprintReset)` of every `onMaintenanceStarted`. */
    val started: List<Pair<MaintenanceOp, Boolean>> get() = synchronized(events) { events.filterIsInstance<Started>().map { it.op to it.reset } }

    val finished: List<MaintenanceOutcome> get() = synchronized(events) { events.filterIsInstance<MaintenanceOutcome>() }

    /** The `fingerprintReset` flags of every `onMaintenanceStarted` for [op]. */
    fun startedFor(op: MaintenanceOp): List<Boolean> = started.filter { it.first == op }.map { it.second }

    override fun onMaintenanceStarted(op: MaintenanceOp, fingerprintReset: Boolean) {
        synchronized(events) { events += Started(op, fingerprintReset) }
        onStarted?.invoke(op)
    }

    override fun onMaintenanceFinished(outcome: MaintenanceOutcome) {
        synchronized(events) { events += outcome }
        failNextFinished?.let {
            failNextFinished = null
            throw it
        }
    }

    private class Started(val op: MaintenanceOp, val reset: Boolean)
}

/**
 * A fault hook that a test points at one (op, step) pair: it throws [error] there, or, with [block], suspends the
 * operation until [release] (testing.md § 5, `ThrowingFaultHook` and `BlockingFaultHook`). Every pair it saw is kept.
 */
class ScriptedFaultHook : MaintenanceFaultHook {
    @Volatile private var target: Pair<MaintenanceOp?, PipelineStep>? = null
    @Volatile private var error: Throwable? = null
    @Volatile private var beforeThrow: () -> Unit = {}
    @Volatile private var gate: CompletableDeferred<Unit>? = null
    private val seen = mutableListOf<Pair<MaintenanceOp?, PipelineStep>>()

    /** Reached by the blocked operation. */
    val blocked = CompletableDeferred<Unit>()

    /** Throws [error] at ([op], [step]), after [before] ran there: a fault that also changes what follows it. */
    fun throwAt(op: MaintenanceOp?, step: PipelineStep, error: Throwable, before: () -> Unit = {}) {
        target = op to step
        this.error = error
        beforeThrow = before
    }

    fun blockAt(op: MaintenanceOp?, step: PipelineStep) {
        target = op to step
        gate = CompletableDeferred()
    }

    fun release() = gate?.complete(Unit)

    /** No pair is targeted any more. */
    fun clear() {
        target = null
        error = null
        beforeThrow = {}
        gate = null
    }

    fun steps(op: MaintenanceOp?): List<PipelineStep> = synchronized(seen) { seen.filter { it.first == op }.map { it.second } }

    override suspend fun before(op: MaintenanceOp?, step: PipelineStep) {
        synchronized(seen) { seen += op to step }
        if (target != (op to step)) return
        error?.let {
            beforeThrow()
            throw it
        }
        gate?.let {
            blocked.complete(Unit)
            it.await()
        }
    }
}

/**
 * The production DbMaintenance over [databases]' files, the production vault of [vaults] (real AndroidKeyStore, the
 * fast test KDF) and a production holder, as the container wires them (architecture.md § Dependency injection).
 * [start] runs what effects E1 and E2 do, without SessionGate.
 */
class MaintenanceSetup(
    databases: TestDatabases,
    vaults: KeyVaultRule,
    applicationScope: CoroutineScope,
    val settings: FakeSettingsStore = FakeSettingsStore().apply { load() },
    val time: FakeAppTime = FakeAppTime(),
    val kek: FaultyKekKeystore = FaultyKekKeystore(),
    val bio: FaultyBioKeystore = FaultyBioKeystore(),
    val files: DbFiles = maintenanceFiles(databases, vaults),
    dispatchers: AppDispatchers = AppDispatchers.production(),
    wrapVault: (KeyVault) -> KeyVault = { it },
    wrapHolder: (DatabaseHolder) -> DatabaseHolder = { it },
) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!
    val hook = ScriptedFaultHook()
    val listener = RecordingMaintenanceListener()
    val vault: DefaultKeyVault = vaults.vault(settings, time, AppDispatchers.production(), kek, bio = bio)
    val holder: DefaultDatabaseHolder = databases.newHolder()
    val maintenance = DefaultDbMaintenance(
        context, files, wrapVault(vault), wrapHolder(holder), settings, time, dispatchers, applicationScope, hook,
    ) { listener }

    /** Effects E1 and E2: reconcile, complete, unlock with [password] (`passwordBytes`), open; asserts the open. */
    suspend fun start(password: String? = null) {
        maintenance.reconcileAtStartup()
        assertEquals(StartupCompletion.Done, maintenance.completeStartup())
        val state = checkNotNull(vault.readState())
        val key = (vault.unlock(password?.let(::passwordBytesOf)) as UnlockResult.Key).rawKey
        assertEquals(OpenResult.Opened, holder.open(state.generation, key))
    }

    /** E3 then E1 and E2 again: a cold start of the same files. */
    suspend fun restart(password: String? = null) {
        holder.close()
        start(password)
    }

    suspend fun state(): KeyState = checkNotNull(vault.readState())

    /** The live generation's file of the open holder. */
    fun liveFile(): File = files.live((holder.state.value as HolderState.Open).generation)

    /** `canonicalDump` of the open live generation through the holder's own instance. */
    fun dump() = canonicalDump(holder.openDatabase().openHelper.writableDatabase)

    /** An unlock cipher authorized as by a prompt (the test configuration needs no touch). */
    suspend fun unlockCipher(): SlotCipher = (vault.biometricUnlockCipher() as SlotCipherResult.Ready).slotCipher

    /** An arm cipher authorized as by a prompt. */
    suspend fun armCipher(): SlotCipher = (vault.biometricArmCipher() as SlotCipherResult.Ready).slotCipher

    /** The mode change through the production entry point; asserts it succeeded. */
    suspend fun changeKey(target: KeyMode, current: String? = null, new: String? = null) {
        val result = maintenance.changeKey(target, current?.toCharArray(), new?.toCharArray())
        assertEquals(result.toString(), true, (result as? AppResult.Ok)?.value is MaintenanceResult.KeyChanged)
    }
}

/** The bytes production derives the key from for [password] (UTF-8 of NFC, security.md § 6.3). */
fun passwordBytesOf(password: String): ByteArray = checkNotNull(passwordBytes(password.toCharArray()))

/**
 * [databases]' files with `noBackupFilesDir` at the directory of [vaults]' `db-state.json`, as on the device: the
 * directory sync of every commit then targets the key-state's own directory. [cacheDir] holds `share/`.
 */
fun maintenanceFiles(databases: TestDatabases, vaults: KeyVaultRule, cacheDir: File = databases.files.shareDir.parentFile!!) =
    DbFiles(databases.files.databasesDir, checkNotNull(vaults.stateFile.parentFile), cacheDir)

/**
 * A vault whose read right after the next commit throws [failure] once, for the `CommitOutcome.Unknown` cases of
 * testing.md L-14 and L-16 (a read-back that fails although the commit landed).
 */
class ReadBackFailingVault(private val delegate: KeyVault, private val failure: () -> Throwable) : KeyVault by delegate {
    /** Set by the test: the next commit arms one failing read. */
    @Volatile
    var failReadAfterNextCommit = false

    @Volatile
    private var armed = false

    override suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?) {
        delegate.commit(generation, slot, bio)
        if (failReadAfterNextCommit) {
            failReadAfterNextCommit = false
            armed = true
        }
    }

    override suspend fun readState(): KeyState? {
        if (armed) {
            armed = false
            throw failure()
        }
        return delegate.readState()
    }
}

/**
 * A vault with scripted answers for the classification and slot-commit tests (testing.md L-13, L-41): it counts
 * every `commit`, makes the next one throw [failNextCommit] before anything is written, and answers the next
 * `unlockWithBiometric` with [nextBiometricUnlock] without using the cipher.
 */
class ScriptedVault(private val delegate: KeyVault) : KeyVault by delegate {
    @Volatile
    var failNextCommit: Throwable? = null

    @Volatile
    var nextBiometricUnlock: UnlockResult? = null

    private val commitCalls = AtomicInteger()

    /** Every `commit` call, the failed ones included. */
    val commits: Int get() = commitCalls.get()

    override suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?) {
        commitCalls.incrementAndGet()
        failNextCommit?.let {
            failNextCommit = null
            throw it
        }
        delegate.commit(generation, slot, bio)
    }

    override suspend fun unlockWithBiometric(cipher: SlotCipher): UnlockResult =
        nextBiometricUnlock?.also { nextBiometricUnlock = null } ?: delegate.unlockWithBiometric(cipher)
}
