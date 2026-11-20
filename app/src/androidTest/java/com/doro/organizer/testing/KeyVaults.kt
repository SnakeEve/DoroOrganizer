package com.doro.organizer.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.debug.testBioKeySpec
import com.doro.organizer.security.AndroidBioKeystore
import com.doro.organizer.security.AndroidKekKeystore
import com.doro.organizer.security.BioKeystore
import com.doro.organizer.security.CALIBRATION_ITERATIONS
import com.doro.organizer.security.DefaultKeyVault
import com.doro.organizer.security.KEK_ALIAS
import com.doro.organizer.security.KekKeystore
import com.doro.organizer.security.isBioKeyAlias
import com.doro.organizer.security.pbkdf2Sha256
import com.doro.organizer.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.Cipher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

/**
 * A fresh key-state directory per test and no KEK or biometric key before or after it, so vault tests never see
 * each other's Keystore entries (the Orchestrator's `pm clear` removes them as well). Declare it as a `@get:Rule`.
 */
class KeyVaultRule : ExternalResource() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!

    /** `db-state.json` in this test's own directory. */
    lateinit var stateFile: File
        private set

    override fun before() {
        deleteKekAlias()
        deleteBioAliases()
        val root = File(context.noBackupFilesDir, "test-key-vaults")
        check(root.isDirectory || root.mkdirs()) { "cannot create the key-state test root" }
        stateFile = File(Files.createTempDirectory(root.toPath(), "kv").toFile(), DefaultKeyVault.STATE_FILE_NAME)
    }

    override fun after() {
        stateFile.parentFile?.deleteRecursively()
        deleteKekAlias()
        deleteBioAliases()
    }

    /** The production vault on [stateFile] with the given seams. */
    fun vault(
        settings: SettingsStore,
        time: FakeAppTime,
        dispatchers: AppDispatchers,
        kek: KekKeystore = AndroidKekKeystore.forDevice(context),
        newSecret: (Int) -> ByteArray = ::ByteArray,
        kdf: (ByteArray, ByteArray, Int) -> ByteArray = TestKdf(time),
        bio: BioKeystore = FaultyBioKeystore(),
    ) = DefaultKeyVault(stateFile, kek, settings, time, dispatchers, newSecret, kdf, bio)

    fun kekExists(): Boolean = androidKeyStore().containsAlias(KEK_ALIAS)

    fun deleteKekAlias() = androidKeyStore().deleteEntry(KEK_ALIAS)

    /** Every biometric key alias in the Keystore (each slot and each arming has its own, DEC-98). */
    fun bioAliases(): Set<String> = bioKeyAliases()

    /** Whether any biometric key exists. */
    fun bioKeyExists(): Boolean = bioAliases().isNotEmpty()

    /** Deletes every biometric key, as an enrollment change kills the committed slot's. */
    fun deleteBioAliases() = androidKeyStore().run { bioAliases().forEach(::deleteEntry) }

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

/** Every biometric key alias in the app's AndroidKeyStore, in any form ever written (DEC-98). */
fun bioKeyAliases(): Set<String> =
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().filterTo(mutableSetOf(), ::isBioKeyAlias)

/** Every dispatcher of the vault on the test scheduler, so the 1 s Keystore retry runs in virtual time. */
fun TestScope.testDispatchers(): AppDispatchers = StandardTestDispatcher(testScheduler).let { AppDispatchers(it, it, it) }

/**
 * The allocation seam of security.md § 3.6: records every array it hands out, so a test can check that all of them
 * except the returned keys are zero when a call returns.
 */
class SecretRecorder : (Int) -> ByteArray {
    private val handedOut = mutableListOf<ByteArray>()

    override fun invoke(size: Int): ByteArray = ByteArray(size).also { synchronized(handedOut) { handedOut += it } }

    fun arrays(): List<ByteArray> = synchronized(handedOut) { handedOut.toList() }

    fun clear() = synchronized(handedOut) { handedOut.clear() }

    /** Fails unless every recorded array other than [kept] (compared by identity) is all zero. */
    fun assertAllZeroExcept(vararg kept: ByteArray) {
        val others = arrays().filter { array -> kept.none { it === array } }
        assertTrue("no secret was handed out", arrays().isNotEmpty())
        others.forEach { array -> assertTrue("a ${array.size}-byte secret was not zeroed", array.all { it == 0.toByte() }) }
    }
}

/**
 * The derivation seam of security.md § 3.6: the real PBKDF2-HMAC-SHA256 at 1/10,000 of the iterations, so tests stay
 * fast while a wrong password still fails the tag. It records every iteration count and output, and each calibration
 * run of 50,000 iterations moves the monotonic clock by [calibrationMillis], which fixes the calibrated count
 * (75 ms gives 600,000). [onDerive] runs inside every derivation except the calibration runs, so a test can cancel
 * the caller or make the derivation throw.
 */
class TestKdf(private val time: FakeAppTime, var calibrationMillis: Long = 75) : (ByteArray, ByteArray, Int) -> ByteArray {
    private val calls = mutableListOf<Int>()
    private val results = mutableListOf<ByteArray>()

    @Volatile
    var onDerive: () -> Unit = {}

    override fun invoke(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        if (iterations == CALIBRATION_ITERATIONS) time.setElapsed(time.elapsedMillis() + calibrationMillis) else onDerive()
        return pbkdf2Sha256(password, salt, maxOf(1, iterations / 10_000)).also {
            synchronized(calls) {
                calls += iterations
                results += it
            }
        }
    }

    /** The iteration counts of every derivation except the calibration runs. */
    fun derivations(): List<Int> = synchronized(calls) { calls.filter { it != CALIBRATION_ITERATIONS } }

    /** Every array the derivation returned, calibration runs included. */
    fun outputs(): List<ByteArray> = synchronized(calls) { results.toList() }
}

/** The operations of [KekKeystore], for scripting failures. */
enum class KekOp { ContainsAlias, Generate, Delete, InitEncrypt, InitDecrypt, DoFinal }

/**
 * The KEK seam of security.md § 3.6 over the real AndroidKeyStore, with scripted failures: each [failNext] makes the
 * next call of that operation throw. [hasStrongBox] is what the vault is told; a StrongBox generation is recorded
 * in [generated] but delegated without StrongBox, so the StrongBox paths run on any phone.
 */
class FaultyKekKeystore(
    override val hasStrongBox: Boolean = false,
    private val delegate: KekKeystore = AndroidKekKeystore(hasStrongBox = false),
) : KekKeystore {
    private val failures = mutableMapOf<KekOp, ArrayDeque<Exception>>()
    private val counts = mutableMapOf<KekOp, Int>()

    /** The `strongBox` argument of every generation, failed ones included. */
    val generated = mutableListOf<Boolean>()

    fun failNext(op: KekOp, error: Exception) = synchronized(this) {
        failures.getOrPut(op) { ArrayDeque() }.addLast(error)
    }

    fun calls(op: KekOp): Int = synchronized(this) { counts[op] ?: 0 }

    override fun containsAlias(): Boolean = call(KekOp.ContainsAlias) { delegate.containsAlias() }

    override fun generate(strongBox: Boolean) {
        synchronized(this) { generated += strongBox }
        call(KekOp.Generate) { delegate.generate(strongBox = false) }
    }

    override fun delete() = call(KekOp.Delete) { delegate.delete() }

    override fun initEncrypt(): Cipher = call(KekOp.InitEncrypt) { delegate.initEncrypt() }

    override fun initDecrypt(iv: ByteArray): Cipher = call(KekOp.InitDecrypt) { delegate.initDecrypt(iv) }

    override fun doFinal(cipher: Cipher, input: ByteArray): ByteArray = call(KekOp.DoFinal) { delegate.doFinal(cipher, input) }

    private fun <T> call(op: KekOp, block: () -> T): T {
        val failure = synchronized(this) {
            counts[op] = (counts[op] ?: 0) + 1
            failures[op]?.removeFirstOrNull()
        }
        if (failure != null) throw failure
        return block()
    }
}

/** The operations of [BioKeystore], for scripting failures. */
enum class BioOp { Aliases, ContainsAlias, Generate, Delete, InitEncrypt, InitDecrypt, UpdateAad, DoFinal }

/**
 * The biometric seam of security.md § 3.6 over the real AndroidKeyStore in the test configuration, with scripted
 * failures: each [failNext] makes the next call of that operation throw. [doFinalOutputs] keeps every array that
 * `doFinal` returned, so a test can check that the vault zeroed the provider-allocated plaintext; [replaceNextOutput]
 * swaps the next `doFinal` result for an array of another length.
 */
class FaultyBioKeystore(private val delegate: BioKeystore = AndroidBioKeystore(::testBioKeySpec)) : BioKeystore {
    private val failures = mutableMapOf<BioOp, ArrayDeque<Exception>>()
    private val counts = mutableMapOf<BioOp, Int>()
    private val outputs = mutableListOf<ByteArray>()
    private var nextOutputSize: Int? = null

    fun failNext(op: BioOp, error: Exception) = synchronized(this) {
        failures.getOrPut(op) { ArrayDeque() }.addLast(error)
    }

    fun replaceNextOutput(size: Int) = synchronized(this) { nextOutputSize = size }

    fun calls(op: BioOp): Int = synchronized(this) { counts[op] ?: 0 }

    /** The number of calls of every operation together. */
    fun totalCalls(): Int = synchronized(this) { counts.values.sum() }

    fun doFinalOutputs(): List<ByteArray> = synchronized(this) { outputs.toList() }

    override fun aliases(): List<String> = call(BioOp.Aliases) { delegate.aliases() }

    override fun containsAlias(alias: String): Boolean = call(BioOp.ContainsAlias) { delegate.containsAlias(alias) }

    override fun generate(alias: String) = call(BioOp.Generate) { delegate.generate(alias) }

    override fun delete(alias: String) = call(BioOp.Delete) { delegate.delete(alias) }

    override fun initEncrypt(alias: String): Cipher = call(BioOp.InitEncrypt) { delegate.initEncrypt(alias) }

    override fun initDecrypt(alias: String, iv: ByteArray): Cipher =
        call(BioOp.InitDecrypt) { delegate.initDecrypt(alias, iv) }

    override fun updateAad(cipher: Cipher, aad: ByteArray) = call(BioOp.UpdateAad) { delegate.updateAad(cipher, aad) }

    override fun doFinal(cipher: Cipher, input: ByteArray): ByteArray = call(BioOp.DoFinal) {
        val real = delegate.doFinal(cipher, input)
        val size = synchronized(this) { nextOutputSize.also { nextOutputSize = null } }
        val result = if (size == null) real else ByteArray(size) { 0x33 }.also { real.fill(0) }
        synchronized(this) { outputs += result }
        result
    }

    private fun <T> call(op: BioOp, block: () -> T): T {
        val failure = synchronized(this) {
            counts[op] = (counts[op] ?: 0) + 1
            failures[op]?.removeFirstOrNull()
        }
        if (failure != null) throw failure
        return block()
    }
}
