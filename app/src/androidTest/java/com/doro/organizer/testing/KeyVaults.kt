package com.doro.organizer.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.security.AndroidKekKeystore
import com.doro.organizer.security.CALIBRATION_ITERATIONS
import com.doro.organizer.security.DefaultKeyVault
import com.doro.organizer.security.KEK_ALIAS
import com.doro.organizer.security.KekKeystore
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
 * A fresh key-state directory per test and no KEK before or after it, so vault tests never see each other's
 * Keystore entries (the Orchestrator's `pm clear` removes them as well). Declare it as a `@get:Rule`.
 */
class KeyVaultRule : ExternalResource() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!

    /** `db-state.json` in this test's own directory. */
    lateinit var stateFile: File
        private set

    override fun before() {
        deleteKekAlias()
        val root = File(context.noBackupFilesDir, "test-key-vaults")
        check(root.isDirectory || root.mkdirs()) { "cannot create the key-state test root" }
        stateFile = File(Files.createTempDirectory(root.toPath(), "kv").toFile(), DefaultKeyVault.STATE_FILE_NAME)
    }

    override fun after() {
        stateFile.parentFile?.deleteRecursively()
        deleteKekAlias()
    }

    /** The production vault on [stateFile] with the given seams. */
    fun vault(
        settings: SettingsStore,
        time: FakeAppTime,
        dispatchers: AppDispatchers,
        kek: KekKeystore = AndroidKekKeystore.forDevice(context),
        newSecret: (Int) -> ByteArray = ::ByteArray,
        kdf: (ByteArray, ByteArray, Int) -> ByteArray = TestKdf(time),
    ) = DefaultKeyVault(stateFile, kek, settings, time, dispatchers, newSecret, kdf)

    fun kekExists(): Boolean = androidKeyStore().containsAlias(KEK_ALIAS)

    fun deleteKekAlias() = androidKeyStore().deleteEntry(KEK_ALIAS)

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

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
