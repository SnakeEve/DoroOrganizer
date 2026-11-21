package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReminder
import com.doro.organizer.settings.Settings
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.FaultyKekKeystore
import com.doro.organizer.testing.KekOp
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.SecretRecorder
import com.doro.organizer.testing.TEST_SLOT_KEY_ALIAS
import com.doro.organizer.testing.TestKdf
import com.doro.organizer.testing.assertThrowsSuspending
import com.doro.organizer.testing.testDispatchers
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `KeyVaultTest` of testing.md L-03 and L-04, and the backoff, reminder and commit rules of security.md § 3.6, § 5.3
 * and § 5.6, on the real AndroidKeyStore through the KEK seam. Every test runs under a timeout, so a re-entrant lock
 * of the vault's mutex fails instead of hanging (security.md § 3.6).
 */
@RunWith(AndroidJUnit4::class)
class KeyVaultTest {
    @get:Rule
    val rule = KeyVaultRule()

    private val time = FakeAppTime()
    private val settings = FakeSettingsStore()
    private val secrets = SecretRecorder()
    private val kdf = TestKdf(time)
    private var kek = FaultyKekKeystore()

    private val password = "correct horse 42".toByteArray()
    private val wrongPassword = "wrong horse 42".toByteArray()

    private fun TestScope.vault() = rule.vault(settings, time, testDispatchers(), kek, secrets, kdf)

    /** Prepares [mode] and commits it as generation 1, as `DbMaintenance.commitGeneration` would. */
    private suspend fun KeyVault.install(mode: KeyMode, newPassword: ByteArray? = null): PreparedKey =
        prepare(mode, newPassword?.copyOf()).also { commit(1, it.slot, null) }

    private fun runVaultTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 60.seconds) { body() }

    /** Makes `db-state.json` a non-empty directory: it cannot be opened, and a rename onto it fails. */
    private fun occupyTheStateFile() =
        check(File(rule.stateFile, "occupied").let { it.parentFile!!.mkdirs() && it.createNewFile() })

    // --- key-state file ---

    @Test
    fun readState_withoutAFile_isNull() = runVaultTest {
        assertNull(vault().readState())
    }

    @Test
    fun readState_withOnlyAnInterruptedFirstWrite_isNull() = runVaultTest {
        File(rule.stateFile.path + ".new").writeText("{\"formatVersion\":1")

        assertNull(vault().readState())
    }

    @Test
    fun readState_ofABaseFileThatCannotBeOpened_isInvalid_neverMissing() = runVaultTest {
        occupyTheStateFile()

        val failure = assertThrowsSuspending<KeyVaultException> { vault().readState() }

        assertEquals(KeyFailure.InvalidState, failure.failure)
    }

    @Test
    fun commit_writesExactlyItsInputs_andReadStateReturnsThem() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        val bio = BioSlot(TEST_SLOT_KEY_ALIAS, ByteArray(12) { 1 }, ByteArray(48) { 2 })

        vault.commit(7, prepared.slot, bio)
        val withBio = requireNotNull(vault.readState())
        vault.commit(7, prepared.slot, null)
        val withoutBio = requireNotNull(vault.readState())

        assertEquals(7L, withBio.generation)
        assertTrue(withBio.slot.sameAs(prepared.slot))
        assertTrue(bio.sameAs(requireNotNull(withBio.bioSlot)))
        assertFalse(withBio.deadBioSlot)
        assertTrue(withoutBio.slot.sameAs(prepared.slot))
        assertNull(withoutBio.bioSlot)
        assertFalse(withoutBio.deadBioSlot)
    }

    @Test
    fun commit_aBiometricSlotNextToANonPasswordSlot_isRefused() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.DEVICE)
        val before = rule.stateFile.readBytes()
        val device = requireNotNull(vault.readState()).slot

        assertThrowsSuspending<IllegalArgumentException> { vault.commit(2, device, BioSlot(TEST_SLOT_KEY_ALIAS, ByteArray(12), ByteArray(48))) }

        assertArrayEquals(before, rule.stateFile.readBytes())
    }

    @Test
    fun commit_aBiometricSlotNamingAKeyOutsideTheSlotAliasForm_isRefusedBeforeWriting() = runVaultTest {
        val vault = vault()
        val slot = vault.install(KeyMode.PASSWORD, password).slot
        val before = rule.stateFile.readBytes()

        // The parse-back check (security.md § 3.5): the strict parser would drop such a slot, so it is never written.
        for (alias in listOf(KEK_ALIAS, "doro_bio_v1")) {
            assertThrowsSuspending<IllegalStateException> { vault.commit(1, slot, BioSlot(alias, ByteArray(12), ByteArray(48))) }
        }

        assertArrayEquals(before, rule.stateFile.readBytes())
    }

    @Test
    fun commit_aSerializationThatDoesNotParseBack_isRefused_andTheFileIsUnchanged() = runVaultTest {
        val vault = vault()
        val slot = vault.install(KeyMode.DEVICE).slot
        val before = rule.stateFile.readBytes()
        val shortWrap = KeySlot(KeyMode.DEVICE, slot.outerIv, slot.wrappedDek?.copyOf(47), null, null)

        assertThrowsSuspending<IllegalStateException> { vault.commit(0, slot, null) }
        assertThrowsSuspending<IllegalStateException> { vault.commit(2, shortWrap, null) }

        assertArrayEquals(before, rule.stateFile.readBytes())
    }

    @Test
    fun aCommitWhoseRenameFails_appliesNoSettingsEffect() = runVaultTest {
        val vault = vault()
        settings.setBackoff(failures = 3, retryAtElapsedMs = 0)
        val typed = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        occupyTheStateFile() // AtomicFile.finishWrite only logs the failed rename

        vault.commit(1, typed.slot, null)

        assertEquals(Settings(unlockFailures = 3), settings.snapshot())
    }

    @Test
    fun eraseState_deletesTheFileAndTheKek() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.DEVICE)
        assertTrue(rule.kekExists())

        vault.eraseState()

        assertNull(vault.readState())
        assertFalse(rule.stateFile.exists())
        assertFalse(rule.kekExists())
    }

    @Test
    fun deleteKek_removesTheAlias_andADeviceSlotThenFails() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.DEVICE)

        vault.deleteKek()
        vault.deleteKek() // a failed or repeated delete is ignored

        assertFalse(rule.kekExists())
        assertEquals(UnlockResult.KeyFailed, vault.unlock(null))
    }

    // --- unlock per mode ---

    @Test
    fun plain_unlocksWithTheEmptyKey_andCreatesNoKek() = runVaultTest {
        val vault = vault()
        val prepared = vault.install(KeyMode.PLAIN)

        val result = vault.unlock(null) as UnlockResult.Key

        assertEquals(0, prepared.rawKey.size)
        assertEquals(0, result.rawKey.size)
        assertFalse(rule.kekExists())
    }

    @Test
    fun device_unlockReturnsThePreparedRawKey() = runVaultTest {
        val vault = vault()
        val prepared = vault.install(KeyMode.DEVICE)

        val result = vault.unlock(null) as UnlockResult.Key

        assertEquals(67, result.rawKey.size)
        assertArrayEquals(prepared.rawKey, result.rawKey)
        assertEquals(listOf(false), kek.generated)
    }

    @Test
    fun password_rightPasswordOpens_wrongIsRejected_noPasswordIsNeeded() = runVaultTest {
        val vault = vault()
        val prepared = vault.install(KeyMode.PASSWORD, password)

        assertEquals(UnlockResult.PasswordNeeded, vault.unlock(null))
        assertEquals(UnlockResult.PasswordRejected(1, 0), vault.unlock(wrongPassword))
        assertArrayEquals(prepared.rawKey, (vault.unlock(password) as UnlockResult.Key).rawKey)
    }

    @Test
    fun password_unlockAlwaysUsesTheStoredIterations() = runVaultTest {
        kdf.calibrationMillis = 40 // calibrates to 940,000
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        val stored = requireNotNull(requireNotNull(vault.readState()).slot.kdf)
        kdf.calibrationMillis = 5 // a later calibration would give 5,000,000; unlock never calibrates

        vault.unlock(password)

        assertEquals(940_000, stored.iterations)
        assertEquals(16, stored.salt.size)
        assertEquals(listOf(940_000, 940_000), kdf.derivations())
    }

    @Test
    fun openKey_opensTheGivenSlot() = runVaultTest {
        val vault = vault()
        val device = vault.prepare(KeyMode.DEVICE, null)
        val typed = vault.prepare(KeyMode.PASSWORD, password.copyOf())

        assertArrayEquals(device.rawKey, (vault.openKey(device.slot, null) as UnlockResult.Key).rawKey)
        assertArrayEquals(typed.rawKey, (vault.openKey(typed.slot, password) as UnlockResult.Key).rawKey)
        assertEquals(UnlockResult.PasswordNeeded, vault.openKey(typed.slot, null))
        assertEquals(0, (vault.openKey(KeySlot(KeyMode.PLAIN, null, null, null, null), null) as UnlockResult.Key).rawKey.size)
    }

    @Test
    fun unlock_withoutAKeyState_isKeyFailed() = runVaultTest {
        assertEquals(UnlockResult.KeyFailed, vault().unlock(password))
    }

    @Test
    fun verifyPassword_onAPasswordSlot_provesThePassword() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        time.advanceBy(Duration.ofDays(1)) // past the timestamp of the commit

        assertEquals(UnlockResult.PasswordRejected(1, 0), vault.verifyPassword(wrongPassword))
        assertEquals(UnlockResult.Verified(), vault.verifyPassword(password))
        assertEquals(Settings(passwordVerifiedAt = time.nowMillis()), settings.snapshot())
    }

    @Test
    fun verifyPassword_onADeviceSlot_isKeyFailed() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.DEVICE)

        assertEquals(UnlockResult.KeyFailed, vault.verifyPassword(password))
        assertEquals(Settings(), settings.snapshot())
    }

    @Test
    fun prepare_refusesAPasswordThatDoesNotFitTheMode() = runVaultTest {
        val vault = vault()

        assertThrowsSuspending<IllegalArgumentException> { vault.prepare(KeyMode.PASSWORD, ByteArray(0)) }
        assertThrowsSuspending<IllegalArgumentException> { vault.prepare(KeyMode.PASSWORD, null) }
        assertThrowsSuspending<IllegalArgumentException> { vault.prepare(KeyMode.DEVICE, password.copyOf()) }
        assertThrowsSuspending<IllegalArgumentException> { vault.prepare(KeyMode.PLAIN, password.copyOf()) }
    }

    // --- zeroing (L-03) ---

    @Test
    fun zeroing_deviceUnlockAndPrepare_leaveOnlyTheReturnedRawKey() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.DEVICE, null)
        secrets.assertAllZeroExcept(prepared.rawKey)
        vault.commit(1, prepared.slot, null)

        secrets.clear()
        val unlocked = vault.unlock(null) as UnlockResult.Key
        secrets.assertAllZeroExcept(unlocked.rawKey)

        secrets.clear()
        val opened = vault.openKey(prepared.slot, null) as UnlockResult.Key
        secrets.assertAllZeroExcept(opened.rawKey)
    }

    @Test
    fun zeroing_passwordPrepareUnlockOpenKeyVerify_leaveOnlyTheReturnedRawKey() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        secrets.assertAllZeroExcept(prepared.rawKey)
        assertKdfOutputsZeroed()
        vault.commit(1, prepared.slot, null)

        secrets.clear()
        val unlocked = vault.unlock(password) as UnlockResult.Key
        secrets.assertAllZeroExcept(unlocked.rawKey)

        secrets.clear()
        val opened = vault.openKey(prepared.slot, password) as UnlockResult.Key
        secrets.assertAllZeroExcept(opened.rawKey)

        secrets.clear()
        assertEquals(UnlockResult.Verified(), vault.verifyPassword(password))
        secrets.assertAllZeroExcept()

        secrets.clear()
        assertEquals(UnlockResult.PasswordRejected(1, 0), vault.unlock(wrongPassword))
        secrets.assertAllZeroExcept()
        assertKdfOutputsZeroed()
    }

    @Test
    fun theVaultNeverZeroesItsInputs() = runVaultTest {
        val vault = vault()
        val typed = password.copyOf()
        vault.install(KeyMode.PASSWORD, typed)

        vault.unlock(typed)

        assertArrayEquals(password, typed)
    }

    // --- backoff (DEC-62, security.md § 5.3) ---

    @Test
    fun fifthWrongPassword_startsA30sWait_persistedBeforeTheAnswer() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)

        repeat(4) { assertEquals(UnlockResult.PasswordRejected(it + 1, 0), vault.unlock(wrongPassword)) }
        val fifth = vault.unlock(wrongPassword)

        val retryAt = time.elapsedMillis() + 30_000
        assertEquals(UnlockResult.PasswordRejected(5, retryAt), fifth)
        assertEquals(5 to retryAt, backoff())
    }

    @Test
    fun whileThrottled_noDerivationRuns_forAnyCaller_thenACorrectPasswordResetsTheBackoff() = runVaultTest {
        val vault = vault()
        val slot = vault.install(KeyMode.PASSWORD, password).slot
        repeat(5) { vault.unlock(wrongPassword) }
        val retryAt = time.elapsedMillis() + 30_000
        val derivations = kdf.derivations().size

        assertEquals(UnlockResult.Throttled(5, retryAt), vault.unlock(password))
        assertEquals(UnlockResult.Throttled(5, retryAt), vault.openKey(slot, password))
        assertEquals(UnlockResult.Throttled(5, retryAt), vault.verifyPassword(password))
        assertEquals(derivations, kdf.derivations().size)

        time.advanceBy(Duration.ofMillis(30_000)) // both clocks: the timestamp below is the one of this unlock
        assertTrue(vault.unlock(password) is UnlockResult.Key)
        assertEquals(Settings(passwordVerifiedAt = time.nowMillis()), settings.snapshot())
    }

    @Test
    fun emptyPassword_isRejectedWithoutCountingOrDerivation() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        val derivations = kdf.derivations().size

        assertEquals(UnlockResult.PasswordRejected(0, 0), vault.unlock(ByteArray(0)))
        assertEquals(UnlockResult.PasswordRejected(0, 0), vault.verifyPassword(ByteArray(0)))

        assertEquals(derivations, kdf.derivations().size)
        assertEquals(0 to 0L, backoff())
    }

    @Test
    fun afterAReboot_theFullDelayIsReapplied_andWrittenBack() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        settings.setBackoff(failures = 6, retryAtElapsedMs = time.elapsedMillis() + 60_000)
        time.setElapsed(1_000) // elapsed realtime restarted at boot

        val result = vault.unlock(password)

        assertEquals(UnlockResult.Throttled(6, 61_000), result)
        assertEquals(6 to 61_000L, backoff())
    }

    @Test
    fun theWallClockNeverShortensTheWait() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        repeat(5) { vault.unlock(wrongPassword) }

        time.setWallClock(time.instant.plus(Duration.ofDays(2)))

        assertTrue(vault.unlock(password) is UnlockResult.Throttled)
    }

    // --- counting before checking, and SettingsStore write failures (DEC-75, security.md § 3.6) ---

    @Test
    fun anAttempt_isCountedBeforeItsPasswordIsChecked() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        settings.setBackoff(failures = 4, retryAtElapsedMs = 0)
        val fifthWait = time.elapsedMillis() + 30_000
        var duringTheCheck: Pair<Int, Long>? = null
        kdf.onDerive = { duringTheCheck = runBlocking { backoff() } }

        assertTrue(vault.unlock(password) is UnlockResult.Key)

        // A process that dies during the check has already paid for this guess.
        assertEquals(5 to fifthWait, duringTheCheck)
        assertEquals("a correct password resets the count", 0 to 0L, backoff())
    }

    @Test
    fun failingSuccessWrite_stillReturnsTheKey_andTheReminderStaysDue() = runVaultTest {
        val vault = vault()
        val prepared = vault.install(KeyMode.PASSWORD, password)
        time.advanceBy(Duration.ofMillis(PasswordReminder.INTERVAL_MS))
        settings.failEditsFrom = settings.edits + 2 // the count before the check is written, the success write fails

        val result = vault.unlock(password) as UnlockResult.Key

        assertArrayEquals(prepared.rawKey, result.rawKey)
        assertTrue(vault.isPasswordReminderDue())
        assertEquals("with the write-back failing too, the counted attempt stays", 1 to 0L, backoff())
    }

    @Test
    fun failingSuccessWrite_writesBackThePairFromBeforeTheAttempt_soNoWaitIsLeft() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        time.advanceBy(Duration.ofMillis(PasswordReminder.INTERVAL_MS))
        settings.setBackoff(failures = 4, retryAtElapsedMs = 0)
        settings.failPasswordVerified = true

        assertTrue(vault.unlock(password) is UnlockResult.Key)

        assertEquals("the fifth count and its 30 s wait are taken back", 4 to 0L, backoff())
        assertTrue(vault.isPasswordReminderDue())
    }

    @Test
    fun aWrongPasswordsWait_runsFromTheEndOfTheCheck() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        settings.setBackoff(failures = 4, retryAtElapsedMs = 0)
        kdf.onDerive = { time.setElapsed(time.elapsedMillis() + 2_000) } // the derivation takes 2 s

        val result = vault.unlock(wrongPassword)

        val retryAt = time.elapsedMillis() + 30_000
        assertEquals(UnlockResult.PasswordRejected(5, retryAt), result)
        assertEquals(5 to retryAt, backoff())
    }

    @Test
    fun failingCountWrite_checksNoPassword_andAnswersStorageFull_withTheBackoffUnchanged() = runVaultTest {
        val vault = vault()
        val slot = vault.install(KeyMode.PASSWORD, password).slot
        settings.setBackoff(failures = 2, retryAtElapsedMs = 0)
        settings.failEdits = true
        val derivations = kdf.derivations().size
        val storageFull = UnlockResult.StorageFull(2, 0)

        assertEquals(storageFull, vault.unlock(wrongPassword))
        assertEquals("the right password cannot unlock either", storageFull, vault.unlock(password))
        assertEquals(storageFull, vault.openKey(slot, password))
        assertEquals(storageFull, vault.verifyPassword(password))

        assertEquals("no password was checked", derivations, kdf.derivations().size)
        assertEquals(2 to 0L, backoff())
    }

    @Test
    fun failingCountWrite_givesNoExtraGuessAfterARestart_andTheCountCarriesOnOnceSpaceIsFree() = runVaultTest {
        vault().install(KeyMode.PASSWORD, password)
        settings.setBackoff(failures = 2, retryAtElapsedMs = 0)
        settings.failEdits = true
        assertEquals(UnlockResult.StorageFull(2, 0), vault().unlock(wrongPassword))

        // A new process: nothing in memory, and still no password is checked while the count cannot be written.
        val restarted = vault()
        val derivations = kdf.derivations().size
        assertEquals(UnlockResult.StorageFull(2, 0), restarted.unlock(wrongPassword))
        assertEquals(derivations, kdf.derivations().size)

        settings.failEdits = false
        assertEquals(UnlockResult.PasswordRejected(3, 0), restarted.unlock(wrongPassword))
        assertEquals(3 to 0L, backoff())
    }

    @Test
    fun failingSettingsRead_failsClosedWithoutDerivation_andTheReminderIsDue() = runVaultTest {
        val vault = vault()
        val slot = vault.install(KeyMode.PASSWORD, password).slot
        val derivations = kdf.derivations().size
        settings.failReads = true
        val wait = UnlockResult.Throttled(0, time.elapsedMillis() + 30_000)

        assertEquals(wait, vault.unlock(password))
        assertEquals(wait, vault.openKey(slot, password))
        assertEquals(wait, vault.verifyPassword(password))
        assertTrue(vault.isPasswordReminderDue())
        assertEquals(derivations, kdf.derivations().size)

        settings.failReads = false
        assertEquals("the wait still binds once the store reads again", wait, vault.unlock(password))
        time.setElapsed(wait.retryAtElapsedMs)
        assertTrue(vault.unlock(password) is UnlockResult.Key)
        assertFalse(vault.isPasswordReminderDue())
    }

    // --- other failures on the password layer (security.md § 5.2) and cancellation (V16) ---

    @Test
    fun anyOtherPasswordLayerFailure_isKeyFailedAtUnlock_andKeyUnavailableAtPrepare_neverCounted() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        secrets.clear()
        kdf.onDerive = { throw IllegalArgumentException("injected") }

        assertEquals(UnlockResult.KeyFailed, vault.unlock(password))
        assertEquals(UnlockResult.KeyFailed, vault.verifyPassword(password))
        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.PASSWORD, password.copyOf()) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(0 to 0L, backoff())
        secrets.assertAllZeroExcept()
    }

    @Test
    fun anyOtherPasswordLayerFailure_takesBackTheCountedAttempt_andItsWait() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        settings.setBackoff(failures = 4, retryAtElapsedMs = 0)
        kdf.onDerive = { throw IllegalArgumentException("injected") }

        assertEquals(UnlockResult.KeyFailed, vault.unlock(password))

        // DEC-76: errors never count, so the fifth count and its 30 s wait written before the check are gone.
        assertEquals(4 to 0L, backoff())
    }

    @Test
    fun aCancelledCaller_stillReceivesItsKey_andNoOtherSecretIsLeft() = runVaultTest {
        val vault = vault()
        val prepared = vault.install(KeyMode.PASSWORD, password)
        secrets.clear()
        var result: UnlockResult? = null
        lateinit var caller: Job
        kdf.onDerive = { caller.cancel() }

        caller = launch { result = vault.unlock(password) }
        caller.join()

        assertTrue(caller.isCancelled)
        val key = result as UnlockResult.Key
        assertArrayEquals(prepared.rawKey, key.rawKey)
        secrets.assertAllZeroExcept(key.rawKey)
        assertKdfOutputsZeroed()
    }

    // --- reminder timestamp (DEC-74, security.md § 5.6) ---

    @Test
    fun aCorrectPassword_restartsTheReminder_aWrongOneDoesNot() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PASSWORD, password)
        time.advanceBy(Duration.ofMillis(PasswordReminder.INTERVAL_MS))
        assertTrue(vault.isPasswordReminderDue())

        vault.unlock(wrongPassword)
        assertTrue(vault.isPasswordReminderDue())
        vault.unlock(password)
        assertFalse(vault.isPasswordReminderDue())

        time.advanceBy(Duration.ofMillis(PasswordReminder.INTERVAL_MS))
        assertTrue(vault.isPasswordReminderDue())
    }

    @Test
    fun committingTheFreshlyTypedSlot_restartsTheReminder() = runVaultTest {
        val vault = vault()

        vault.install(KeyMode.PASSWORD, password)

        assertEquals(time.nowMillis(), settings.snapshot().passwordVerifiedAt)
        assertFalse(vault.isPasswordReminderDue())
    }

    @Test
    fun otherCommits_leaveTheReminderAlone() = runVaultTest {
        val vault = vault()
        val typed = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.prepare(KeyMode.DEVICE, null) // forgets the freshly typed slot

        vault.commit(1, typed.slot, null)
        vault.install(KeyMode.DEVICE)
        vault.commit(1, KeySlot(KeyMode.PLAIN, null, null, null, null), null)

        assertEquals(0L, settings.snapshot().passwordVerifiedAt)
    }

    // --- commit and the backoff (security.md § 3.6) ---

    @Test
    fun aCommitThatKeepsTheSlot_leavesTheBackoff_aNewSlotResetsIt() = runVaultTest {
        val vault = vault()
        val slot = vault.install(KeyMode.PASSWORD, password).slot
        settings.setBackoff(failures = 5, retryAtElapsedMs = 99_000)

        vault.commit(1, slot, BioSlot(TEST_SLOT_KEY_ALIAS, ByteArray(12), ByteArray(48))) // a biometric slot commit
        vault.commit(2, slot, null) // a restore that keeps the slot
        assertEquals(Settings(unlockFailures = 5, unlockRetryAtElapsedMs = 99_000, passwordVerifiedAt = time.nowMillis()), settings.snapshot())

        vault.install(KeyMode.DEVICE)
        assertEquals(0, settings.snapshot().unlockFailures)
        assertEquals(0L, settings.snapshot().unlockRetryAtElapsedMs)
    }

    // --- KEK lifecycle (security.md § 3.1, § 3.2) ---

    @Test
    fun prepare_generatesTheKekOnlyWhenMissing() = runVaultTest {
        val vault = vault()

        vault.prepare(KeyMode.DEVICE, null)
        vault.prepare(KeyMode.PASSWORD, password.copyOf())

        assertEquals(listOf(false), kek.generated)
    }

    @Test
    fun prepare_strongBoxRefusedAtGeneration_fallsBackWithoutStrongBox() = runVaultTest {
        kek = FaultyKekKeystore(hasStrongBox = true)
        kek.failNext(KekOp.Generate, ProviderException("injected: StrongBox unavailable"))
        val vault = vault()

        val prepared = vault.install(KeyMode.DEVICE)

        assertEquals(listOf(true, false), kek.generated)
        assertArrayEquals(prepared.rawKey, (vault.unlock(null) as UnlockResult.Key).rawKey)
    }

    @Test
    fun prepare_oneTransientKeystoreFailure_isRetriedAfterOneSecond_andTheKekIsKept() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.DEVICE)
        kek.failNext(KekOp.InitEncrypt, KeyStoreException("injected"))
        val start = currentTime

        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())

        assertEquals(1_000L, currentTime - start)
        assertEquals(0, kek.calls(KekOp.Delete))
        assertEquals(listOf(false), kek.generated)
        vault.commit(2, prepared.slot, null)
        assertArrayEquals(prepared.rawKey, (vault.unlock(password) as UnlockResult.Key).rawKey)
    }

    @Test
    fun prepare_newStrongBoxKekFailingItsSelfCheck_isReplacedOnceWithoutStrongBox() = runVaultTest {
        kek = FaultyKekKeystore(hasStrongBox = true)
        repeat(2) { kek.failNext(KekOp.DoFinal, KeyStoreException("injected")) } // the wrap and its retry
        val vault = vault()

        val prepared = vault.install(KeyMode.PASSWORD, password)

        assertEquals(listOf(true, false), kek.generated)
        assertArrayEquals(prepared.rawKey, (vault.unlock(password) as UnlockResult.Key).rawKey)
    }

    @Test
    fun prepare_brokenKekWhileTheCommittedModeIsPlain_isRegeneratedOnce() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PLAIN)
        kek.generate(strongBox = false) // a leftover alias that no committed slot uses
        repeat(2) { kek.failNext(KekOp.InitEncrypt, KeyStoreException("injected")) } // the wrap and its retry

        val prepared = vault.install(KeyMode.DEVICE)

        assertEquals(1, kek.calls(KekOp.Delete))
        assertEquals(listOf(false, false), kek.generated)
        assertArrayEquals(prepared.rawKey, (vault.unlock(null) as UnlockResult.Key).rawKey)
    }

    @Test
    fun prepare_brokenKekUnderACommittedSlot_isKeyUnavailable_andTheKekIsKept() = runVaultTest {
        val vault = vault()
        val committed = vault.install(KeyMode.DEVICE)
        repeat(2) { kek.failNext(KekOp.InitEncrypt, KeyStoreException("injected")) } // the wrap and its retry

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.PASSWORD, password.copyOf()) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(0, kek.calls(KekOp.Delete))
        assertArrayEquals(committed.rawKey, (vault.unlock(null) as UnlockResult.Key).rawKey)
    }

    @Test
    fun prepare_aRegeneratedKekUnderPlainThatFailsAgain_isKeyUnavailable_withoutAnotherRegeneration() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.PLAIN)
        kek.generate(strongBox = false)
        repeat(4) { kek.failNext(KekOp.InitEncrypt, KeyStoreException("injected")) } // both KEKs, each with its retry
        secrets.clear()

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.DEVICE, null) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(1, kek.calls(KekOp.Delete))
        assertEquals(listOf(false, false), kek.generated)
        secrets.assertAllZeroExcept()
    }

    @Test
    fun prepare_aNewKekWithoutStrongBoxFailingItsSelfCheck_isKeyUnavailable_withoutReplacement() = runVaultTest {
        val vault = vault()
        repeat(2) { kek.failNext(KekOp.DoFinal, KeyStoreException("injected")) } // the wrap and its retry

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.PASSWORD, password.copyOf()) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(0, kek.calls(KekOp.Delete))
        assertEquals(listOf(false), kek.generated)
        secrets.assertAllZeroExcept()
        assertKdfOutputsZeroed()
    }

    private suspend fun backoff() = settings.snapshot().let { it.unlockFailures to it.unlockRetryAtElapsedMs }

    private fun assertKdfOutputsZeroed() {
        assertTrue(kdf.outputs().isNotEmpty())
        kdf.outputs().forEach { output -> assertTrue("a derivation output was not zeroed", output.all { it == 0.toByte() }) }
    }
}
