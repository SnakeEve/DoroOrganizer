package com.doro.organizer.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.BioOp
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.FaultyBioKeystore
import com.doro.organizer.testing.FaultyKekKeystore
import com.doro.organizer.testing.KekOp
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.SecretRecorder
import com.doro.organizer.testing.TestKdf
import com.doro.organizer.testing.assertThrowsSuspending
import com.doro.organizer.testing.testDispatchers
import java.security.KeyStoreException
import java.security.ProviderException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The biometric half of `KeyVaultTest` (security.md § 3.6, § 3.7; testing.md L-03, L-30, L-33 vault parts): arming,
 * wrapping and unwrapping through the slot, every slot failure class of § 3.7.4, the KEK check and the KEK
 * replacement of the fingerprint reset, on the real AndroidKeyStore through the biometric seam in its test
 * configuration (no user authentication). Every test runs under a timeout, so a re-entrant lock of the vault's
 * mutex fails instead of hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime
@RunWith(AndroidJUnit4::class)
class KeyVaultBiometricTest {
    @get:Rule
    val rule = KeyVaultRule()

    private val time = FakeAppTime()
    private val settings = FakeSettingsStore()
    private val secrets = SecretRecorder()
    private val kdf = TestKdf(time)
    private val kek = FaultyKekKeystore()
    private val bio = FaultyBioKeystore()

    private val password = "correct horse 42".toByteArray()

    private fun TestScope.vault() = rule.vault(settings, time, testDispatchers(), kek, secrets, kdf, bio)

    private fun runVaultTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 60.seconds) { body() }

    /** A committed `PASSWORD` slot as generation 1 and a biometric slot armed next to it, as `DbMaintenance` would. */
    private suspend fun KeyVault.installWithSlot(): PreparedKey {
        val prepared = prepare(KeyMode.PASSWORD, password.copyOf())
        commit(1, prepared.slot, null)
        commit(1, prepared.slot, armSlot(prepared.rawKey))
        return prepared
    }

    private suspend fun KeyVault.armSlot(liveRawKey: ByteArray): BioSlot {
        val cipher = armCipher()
        return (wrapForBiometric(cipher, liveRawKey) as WrapResult.Wrapped).bioSlot
    }

    private suspend fun KeyVault.unlockCipher(): SlotCipher = (biometricUnlockCipher() as SlotCipherResult.Ready).slotCipher

    private suspend fun KeyVault.armCipher(): SlotCipher = (biometricArmCipher() as SlotCipherResult.Ready).slotCipher

    /** The alias of the committed slot's own key. */
    private suspend fun KeyVault.slotAlias(): String = requireNotNull(requireNotNull(readState()).bioSlot).keyAlias

    /** Rewrites the committed biometric slot with one byte of its wrapped DEK flipped. */
    private suspend fun KeyVault.flipWrappedDekByte() {
        val state = requireNotNull(readState())
        val slot = requireNotNull(state.bioSlot)
        val damaged = slot.wrappedDek.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        commit(state.generation, state.slot, BioSlot(slot.keyAlias, slot.iv, damaged))
    }

    /** Makes the next [times] KEK decryptions fail with a Keystore exception. */
    private fun failKekDecrypt(times: Int) = repeat(times) { kek.failNext(KekOp.InitDecrypt, KeyStoreException("injected")) }

    /** A fingerprint unlock that reports kekBroken for the committed generation. */
    private suspend fun KeyVault.unlockReportingKekBroken() =
        assertTrue((unlockWithBiometric(unlockCipher()) as UnlockResult.Key).kekBroken)

    // --- slot status ---

    @Test
    fun status_isNoneWithoutAFile_inPlainAndDevice_andWithoutABioSlot() = runVaultTest {
        val vault = vault()
        assertEquals(BioSlotStatus.None, vault.biometricSlotStatus())

        vault.commit(1, vault.prepare(KeyMode.PLAIN, null).slot, null)
        assertEquals(BioSlotStatus.None, vault.biometricSlotStatus())

        vault.commit(2, vault.prepare(KeyMode.DEVICE, null).slot, null)
        assertEquals(BioSlotStatus.None, vault.biometricSlotStatus())

        vault.commit(3, vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot, null)
        assertEquals(BioSlotStatus.None, vault.biometricSlotStatus())
    }

    @Test
    fun status_aFailedAliasCheck_isRetriedOnceAfterOneSecond() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        bio.failNext(BioOp.ContainsAlias, KeyStoreException("injected"))
        val start = currentTime

        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())

        assertEquals(1_000L, currentTime - start)
    }

    @Test
    fun status_anAliasCheckThatKeepsFailing_isDead_andDeletesNothing() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val before = rule.stateFile.readBytes()
        repeat(2) { bio.failNext(BioOp.ContainsAlias, KeyStoreException("injected")) }

        // Not Live, so a failed password KEK reaches KeyUnavailable instead of looping through the slot (DEC-86).
        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())

        assertTrue(rule.bioKeyExists())
        assertArrayEquals(before, rule.stateFile.readBytes())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
    }

    @Test
    fun status_isLiveWithTheAlias_andDeadWithoutIt() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())

        rule.deleteBioAliases()

        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
    }

    /** SPEC §B B3: another biometric key, such as an arming's, never makes a slot whose own key is gone read `Live`. */
    @Test
    fun status_isDeadWithoutTheSlotsOwnKey_evenWhileAnotherBiometricKeyExists() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        rule.deleteBioAliases()

        vault.armCipher()

        assertTrue(rule.bioKeyExists())
        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
    }

    @Test
    fun status_startsNoKeystoreOperation() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val before = bio.totalCalls()
        val aliasChecks = bio.calls(BioOp.ContainsAlias)

        vault.biometricSlotStatus()

        assertEquals(before + 1, bio.totalCalls())
        assertEquals(aliasChecks + 1, bio.calls(BioOp.ContainsAlias))
    }

    // --- arming (§ 3.7.2) ---

    @Test
    fun arm_wrapsTheLiveDek_andTheSlotThenReleasesTheSameRawKey() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        val slot = requireNotNull(requireNotNull(vault.readState()).bioSlot)
        val derivationsBefore = kdf.derivations().size

        val unlocked = vault.unlockWithBiometric(vault.unlockCipher()) as UnlockResult.Key

        assertEquals(12, slot.iv.size)
        assertEquals(48, slot.wrappedDek.size)
        assertArrayEquals(prepared.rawKey, unlocked.rawKey)
        assertFalse(unlocked.kekBroken)
        assertEquals(derivationsBefore, kdf.derivations().size) // no PBKDF2 on the slot path
    }

    @Test
    fun arm_outsidePasswordMode_orWithoutAFile_isNoSlot_andGeneratesNoKey() = runVaultTest {
        val vault = vault()
        assertEquals(SlotCipherResult.NoSlot, vault.biometricArmCipher())

        vault.commit(1, vault.prepare(KeyMode.DEVICE, null).slot, null)
        assertEquals(SlotCipherResult.NoSlot, vault.biometricArmCipher())

        assertEquals(0, bio.calls(BioOp.Generate))
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun arm_generatesAKeyUnderAFreshAlias_thatTheWrappedSlotNames() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)

        val first = vault.armCipher()
        val second = vault.armCipher()

        assertTrue(isSlotKeyAlias(first.keyAlias))
        assertTrue(isSlotKeyAlias(second.keyAlias))
        assertNotEquals(first.keyAlias, second.keyAlias)
        assertEquals(second.keyAlias, (vault.wrapForBiometric(second, prepared.rawKey) as WrapResult.Wrapped).bioSlot.keyAlias)
    }

    /** security.md § 3.7.5: an unused key of a cancelled arming goes with the next arming; the slot's key never does. */
    @Test
    fun arm_deletesTheUnusedKeysOfEarlierArmings_butNeverTheCommittedSlotsKey() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val alias = vault.slotAlias()

        vault.armCipher() // a cancelled arming
        val second = vault.armCipher()

        assertEquals(setOf(alias, second.keyAlias), rule.bioAliases())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
    }

    /** DEC-98: a cancelled or interrupted arming leaves the committed slot exactly as it was. */
    @Test
    fun arm_leavesTheCommittedSlotsKey_soALiveSlotStaysLive() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        val alias = vault.slotAlias()

        vault.armCipher() // a cancelled arming: a new key, nothing committed

        assertTrue(alias in rule.bioAliases())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
        assertArrayEquals(prepared.rawKey, (vault.unlockWithBiometric(vault.unlockCipher()) as UnlockResult.Key).rawKey)
    }

    /** SPEC §B B3 (CS-29): arming over a dead slot keeps it dead, so the next start asks for the password. */
    @Test
    fun arm_overADeadSlot_leavesItDead() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        rule.deleteBioAliases() // as after a new enrollment

        vault.armCipher()

        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
        assertEquals(SlotCipherResult.Invalidated, vault.biometricUnlockCipher())
    }

    @Test
    fun arm_aKeystoreFailure_isUnavailable_andLeavesNoKey() = runVaultTest {
        val vault = vault()
        vault.commit(1, vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot, null)
        bio.failNext(BioOp.InitEncrypt, ProviderException("injected"))

        assertEquals(SlotCipherResult.Unavailable, vault.biometricArmCipher())
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun wrap_aFailingCipher_isBiometricUnusable_andDeletesTheKey() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)
        val cipher = vault.armCipher()
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        assertEquals(WrapResult.BiometricUnusable, vault.wrapForBiometric(cipher, prepared.rawKey))
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun wrap_aFailure_deletesOnlyTheCiphersKey_andTheCommittedSlotStaysLive() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        val alias = vault.slotAlias()
        val cipher = vault.armCipher()
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        assertEquals(WrapResult.BiometricUnusable, vault.wrapForBiometric(cipher, prepared.rawKey))

        assertEquals(setOf(alias), rule.bioAliases())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
    }

    @Test
    fun wrap_afterAKeyChangeWasCommitted_isBiometricUnusable_withoutUsingTheCipher() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)
        val cipher = vault.armCipher()
        val changed = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(2, changed.slot, null)

        assertEquals(WrapResult.BiometricUnusable, vault.wrapForBiometric(cipher, changed.rawKey))
        assertEquals(0, bio.calls(BioOp.DoFinal))
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun wrap_persistsNothing_zeroesItsDek_andLeavesTheLiveKeyAlone() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)
        val before = rule.stateFile.readBytes()
        val live = prepared.rawKey.copyOf()
        val cipher = vault.armCipher()
        secrets.clear()

        val wrapped = vault.wrapForBiometric(cipher, live) as WrapResult.Wrapped

        assertEquals(12, wrapped.bioSlot.iv.size)
        assertEquals(48, wrapped.bioSlot.wrappedDek.size)
        assertTrue(rule.bioKeyExists())
        secrets.assertAllZeroExcept()
        assertArrayEquals(prepared.rawKey, live)
        assertArrayEquals(before, rule.stateFile.readBytes())
    }

    @Test
    fun wrap_withoutAKeyStateFile_isBiometricUnusable_andDeletesTheKey() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)
        val cipher = vault.armCipher()
        check(rule.stateFile.delete())

        assertEquals(WrapResult.BiometricUnusable, vault.wrapForBiometric(cipher, prepared.rawKey))
        assertEquals(0, bio.calls(BioOp.DoFinal))
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun aCipherOfTheWrongUse_isACrash_beforeAnyKeystoreCall() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        val unlock = vault.unlockCipher()
        val arm = vault.armCipher()
        val before = bio.totalCalls()

        assertThrowsSuspending<IllegalArgumentException> { vault.unlockWithBiometric(arm) }
        assertThrowsSuspending<IllegalArgumentException> { vault.verifyBiometric(arm) }
        assertThrowsSuspending<IllegalArgumentException> { vault.wrapForBiometric(unlock, prepared.rawKey) }
        assertThrowsSuspending<IllegalArgumentException> { vault.discardArmCipher(unlock) }

        assertEquals(before, bio.totalCalls())
    }

    // --- unwrapping (§ 3.7.3) ---

    @Test
    fun unlockWithBiometric_zeroesEverySecretButTheRawKey_andTheProviderPlaintext() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val cipher = vault.unlockCipher()
        secrets.clear()

        val unlocked = vault.unlockWithBiometric(cipher) as UnlockResult.Key

        secrets.assertAllZeroExcept(unlocked.rawKey)
        val plaintext = bio.doFinalOutputs().last()
        assertEquals(32, plaintext.size)
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test
    fun verifyBiometric_provesTheSlot_andBuildsNoRawKey() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val cipher = vault.unlockCipher()
        secrets.clear()

        assertEquals(UnlockResult.Verified(kekBroken = false), vault.verifyBiometric(cipher))

        assertTrue(secrets.arrays().none { it.size == 67 })
        secrets.assertAllZeroExcept()
        val plaintext = bio.doFinalOutputs().last()
        assertEquals(32, plaintext.size)
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test
    fun theSlotPath_neverTouchesTheBackoffOrTheReminder() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        settings.setBackoff(failures = 3, retryAtElapsedMs = 0)
        val before = settings.snapshot()
        val edits = settings.edits

        vault.unlockWithBiometric(vault.unlockCipher())
        vault.verifyBiometric(vault.unlockCipher())
        vault.flipWrappedDekByte()
        vault.unlockWithBiometric(vault.unlockCipher())

        assertEquals(before, settings.snapshot())
        assertEquals(edits, settings.edits)
    }

    @Test
    fun unlockCipher_withoutABioSlot_outsidePasswordMode_orWithoutAFile_isNoSlot() = runVaultTest {
        val vault = vault()
        assertEquals(SlotCipherResult.NoSlot, vault.biometricUnlockCipher())

        vault.commit(1, vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot, null)
        assertEquals(SlotCipherResult.NoSlot, vault.biometricUnlockCipher())

        vault.commit(2, vault.prepare(KeyMode.DEVICE, null).slot, null)
        assertEquals(SlotCipherResult.NoSlot, vault.biometricUnlockCipher())
    }

    @Test
    fun unlockCipher_aMalformedBioSlot_isNoSlot_andItsKeyIsDeleted() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val json = JSONObject(rule.stateFile.readText())
        json.getJSONObject("bioSlot").put("keyAlias", "doro_bio_v2")
        rule.stateFile.writeText(json.toString())
        assertTrue(requireNotNull(vault.readState()).deadBioSlot) // the edit took effect

        assertEquals(SlotCipherResult.NoSlot, vault.biometricUnlockCipher())
        assertEquals(BioSlotStatus.None, vault.biometricSlotStatus())
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun anInvalidatedKey_isInvalidated_deletesTheAlias_andKeepsTheFile() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val before = rule.stateFile.readBytes()
        bio.failNext(BioOp.InitDecrypt, KeyPermanentlyInvalidatedException())

        assertEquals(SlotCipherResult.Invalidated, vault.biometricUnlockCipher())

        assertEquals(1, bio.calls(BioOp.InitDecrypt)) // never retried
        assertFalse(rule.bioKeyExists())
        assertArrayEquals(before, rule.stateFile.readBytes())
        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
        assertEquals(0, settings.snapshot().unlockFailures)
    }

    @Test
    fun aMissingAlias_isInvalidated_andTheFileKeepsTheDeadSlot() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val before = rule.stateFile.readBytes()
        rule.deleteBioAliases()

        assertEquals(SlotCipherResult.Invalidated, vault.biometricUnlockCipher())

        assertEquals(0, bio.calls(BioOp.InitDecrypt))
        assertArrayEquals(before, rule.stateFile.readBytes())
    }

    @Test
    fun aKeystoreFailureAtInit_isRetriedOnceAfterOneSecond() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        bio.failNext(BioOp.InitDecrypt, ProviderException("injected"))
        val start = currentTime

        assertTrue(vault.biometricUnlockCipher() is SlotCipherResult.Ready)

        assertEquals(1_000L, currentTime - start)
        assertEquals(2, bio.calls(BioOp.InitDecrypt))
    }

    @Test
    fun aPersistentKeystoreFailureAtInit_isUnavailable_thenJudgedDeadTheSecondTime() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }

        assertEquals(SlotCipherResult.Unavailable, vault.biometricUnlockCipher())
        assertTrue(rule.bioKeyExists()) // the slot is kept

        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }

        assertEquals(SlotCipherResult.Invalidated, vault.biometricUnlockCipher())
        assertFalse(rule.bioKeyExists())
        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
    }

    @Test
    fun aSuccessfulUnwrap_resetsTheFailureCount() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }
        assertEquals(SlotCipherResult.Unavailable, vault.biometricUnlockCipher())

        assertTrue(vault.unlockWithBiometric(vault.unlockCipher()) is UnlockResult.Key)
        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }

        assertEquals(SlotCipherResult.Unavailable, vault.biometricUnlockCipher())
        assertTrue(rule.bioKeyExists())
    }

    @Test
    fun aRestartedVault_givesTheSlotTwoMoreChances() = runVaultTest {
        vault().installWithSlot()
        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }
        assertEquals(SlotCipherResult.Unavailable, vault().biometricUnlockCipher())
        val restarted = vault()

        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }
        assertEquals(SlotCipherResult.Unavailable, restarted.biometricUnlockCipher())
        assertTrue(rule.bioKeyExists())

        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }
        assertEquals(SlotCipherResult.Invalidated, restarted.biometricUnlockCipher())
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun anUnavailableCipher_andAnUnwrapFailure_countTogether() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        repeat(2) { bio.failNext(BioOp.InitDecrypt, KeyStoreException("injected")) }
        assertEquals(SlotCipherResult.Unavailable, vault.biometricUnlockCipher())
        val cipher = vault.unlockCipher()
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        assertEquals(UnlockResult.SlotFailed(invalidated = true), vault.unlockWithBiometric(cipher))
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun aNewSlot_startsWithoutTheOldSlotsFailures() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))
        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(vault.unlockCipher()))
        vault.commit(1, prepared.slot, vault.armSlot(prepared.rawKey)) // a new key and a new IV
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(vault.unlockCipher()))
        assertTrue(rule.bioKeyExists())
    }

    @Test
    fun aTagFailure_killsTheSlot_neverWrongPassword_andTheFileIsKept() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        vault.flipWrappedDekByte()
        val before = rule.stateFile.readBytes()

        assertEquals(UnlockResult.SlotFailed(invalidated = true), vault.unlockWithBiometric(vault.unlockCipher()))

        assertFalse(rule.bioKeyExists())
        assertArrayEquals(before, rule.stateFile.readBytes())
        assertEquals(0, settings.snapshot().unlockFailures)
        assertTrue(vault.unlock(password.copyOf()) is UnlockResult.Key) // the password slot still opens
    }

    @Test
    fun aPlaintextOfTheWrongLength_killsTheSlot_andIsZeroed() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val cipher = vault.unlockCipher()
        bio.replaceNextOutput(31)

        assertEquals(UnlockResult.SlotFailed(invalidated = true), vault.verifyBiometric(cipher))

        assertFalse(rule.bioKeyExists())
        assertTrue(bio.doFinalOutputs().last().all { it == 0.toByte() })
    }

    @Test
    fun anOtherUnwrapFailure_keepsTheSlot_thenJudgesItDeadTheSecondTime() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val doFinals = bio.calls(BioOp.DoFinal)
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(vault.unlockCipher()))
        assertTrue(rule.bioKeyExists())

        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        assertEquals(UnlockResult.SlotFailed(invalidated = true), vault.verifyBiometric(vault.unlockCipher()))
        assertFalse(rule.bioKeyExists())
        assertEquals(doFinals + 2, bio.calls(BioOp.DoFinal)) // never retried: a retry needs a new authentication
    }

    @Test
    fun aKeyChangeDuringThePrompt_isAnUncountedSlotFailure_withoutUsingTheCipher() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val original = requireNotNull(vault.readState())
        val doFinals = bio.calls(BioOp.DoFinal)
        repeat(2) {
            val cipher = vault.unlockCipher()
            vault.commit(2, vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot, null)

            assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(cipher))
            vault.commit(1, original.slot, original.bioSlot)
        }
        assertEquals(doFinals, bio.calls(BioOp.DoFinal))
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))

        // Neither race was counted: this is the first counted failure, so the slot is kept.
        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(vault.unlockCipher()))
        assertTrue(rule.bioKeyExists())
    }

    @Test
    fun aKeyChangeDuringTheVerifyPrompt_isAnUncountedSlotFailure_withoutUsingTheCipher() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val cipher = vault.unlockCipher()
        val doFinals = bio.calls(BioOp.DoFinal)
        vault.commit(2, vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot, null)

        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.verifyBiometric(cipher))
        assertEquals(doFinals, bio.calls(BioOp.DoFinal))
    }

    @Test
    fun aSlotRemovedDuringThePrompt_isAnUncountedSlotFailure_withoutUsingTheCipher() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val original = requireNotNull(vault.readState())
        val cipher = vault.unlockCipher()
        val doFinals = bio.calls(BioOp.DoFinal)
        vault.commit(1, original.slot, null)

        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(cipher))
        assertEquals(doFinals, bio.calls(BioOp.DoFinal))

        vault.commit(1, original.slot, original.bioSlot)
        bio.failNext(BioOp.DoFinal, ProviderException("injected"))
        // The race was not counted: this is the first counted failure, so the slot is kept.
        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(vault.unlockCipher()))
        assertTrue(rule.bioKeyExists())
    }

    @Test
    fun aSlotReArmedDuringThePrompt_isAnUncountedSlotFailure_andTheNewSlotSurvives() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        val cipher = vault.unlockCipher()
        vault.commit(1, prepared.slot, vault.armSlot(prepared.rawKey)) // same generation and outerIv, new key and IV
        val doFinals = bio.calls(BioOp.DoFinal)

        assertEquals(UnlockResult.SlotFailed(invalidated = false), vault.unlockWithBiometric(cipher))

        assertEquals(doFinals, bio.calls(BioOp.DoFinal))
        assertTrue(rule.bioKeyExists())
        val unlocked = vault.unlockWithBiometric(vault.unlockCipher()) as UnlockResult.Key
        assertArrayEquals(prepared.rawKey, unlocked.rawKey)
    }

    @Test
    fun unwrap_withoutAKeyStateFile_isKeyFailed() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val cipher = vault.unlockCipher()
        check(rule.stateFile.delete())

        assertEquals(UnlockResult.KeyFailed, vault.unlockWithBiometric(cipher))
    }

    // --- the KEK check and the fingerprint reset's KEK replacement (§ 3.7.3, § 3.1, § 4.3) ---

    @Test
    fun aBrokenKek_isReportedByTheSlotPath_whichStillUnlocks() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        rule.deleteKekAlias()

        val unlocked = vault.unlockWithBiometric(vault.unlockCipher()) as UnlockResult.Key

        assertTrue(unlocked.kekBroken)
        assertArrayEquals(prepared.rawKey, unlocked.rawKey)
        assertEquals(UnlockResult.Verified(kekBroken = true), vault.verifyBiometric(vault.unlockCipher()))
    }

    @Test
    fun aKekFailure_isRetriedOnceBeforeItCountsAsBroken() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        kek.failNext(KekOp.InitDecrypt, KeyStoreException("injected"))
        val start = currentTime

        val unlocked = vault.unlockWithBiometric(vault.unlockCipher()) as UnlockResult.Key

        assertFalse(unlocked.kekBroken)
        assertEquals(1_000L, currentTime - start)
    }

    @Test
    fun aKekCheckThatFailsWithAnUnexpectedException_zeroesTheRawKey() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val cipher = vault.unlockCipher()
        secrets.clear()
        kek.failNext(KekOp.InitDecrypt, IllegalStateException("injected"))

        assertThrowsSuspending<IllegalStateException> { vault.unlockWithBiometric(cipher) }

        assertTrue(secrets.arrays().any { it.size == 67 })
        secrets.assertAllZeroExcept()
    }

    @Test
    fun afterABrokenKek_theResetsPrepareReplacesTheKekOnce() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        rule.deleteKekAlias()
        vault.unlockReportingKekBroken()
        val generated = kek.generated.size
        val deletes = kek.calls(KekOp.Delete)
        val newPassword = "new horse 4242".toByteArray()

        val reset = vault.prepare(KeyMode.PASSWORD, newPassword.copyOf())
        vault.prepare(KeyMode.PASSWORD, newPassword.copyOf())

        assertEquals(generated + 1, kek.generated.size)
        assertEquals(deletes + 1, kek.calls(KekOp.Delete))
        vault.commit(2, reset.slot, null)
        assertArrayEquals(reset.rawKey, (vault.unlock(newPassword) as UnlockResult.Key).rawKey)
    }

    @Test
    fun aKekThatWorksAgain_isReusedByThePrepare_soTheCommittedSlotStillOpens() = runVaultTest {
        val vault = vault()
        val prepared = vault.installWithSlot()
        failKekDecrypt(2) // a transient failure during the fingerprint unlock
        vault.unlockReportingKekBroken()
        val generated = kek.generated.size
        val deletes = kek.calls(KekOp.Delete)

        vault.prepare(KeyMode.PASSWORD, "new horse 4242".toByteArray()) // an operation that then fails before commit

        assertEquals(generated, kek.generated.size)
        assertEquals(deletes, kek.calls(KekOp.Delete))
        assertArrayEquals(prepared.rawKey, (vault.unlock(password.copyOf()) as UnlockResult.Key).rawKey)
    }

    @Test
    fun aFailedKekReplacement_isDoneAgainByTheNextPrepare() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        rule.deleteKekAlias()
        vault.unlockReportingKekBroken()
        repeat(2) { kek.failNext(KekOp.Generate, ProviderException("injected")) }
        val newPassword = "new horse 4242".toByteArray()

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.PASSWORD, newPassword.copyOf()) }
        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        val deletes = kek.calls(KekOp.Delete)

        val reset = vault.prepare(KeyMode.PASSWORD, newPassword.copyOf())

        assertTrue(kek.calls(KekOp.Delete) > deletes) // replaced again, not taken as a merely missing KEK
        vault.commit(2, reset.slot, null)
        assertArrayEquals(reset.rawKey, (vault.unlock(newPassword) as UnlockResult.Key).rawKey)
    }

    @Test
    fun aDevicePrepare_neverReplacesTheKek() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        failKekDecrypt(4) // two for the fingerprint unlock's check, two that a KEK check in the prepare would meet
        vault.unlockReportingKekBroken()
        val generated = kek.generated.size
        val deletes = kek.calls(KekOp.Delete)

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.DEVICE, null) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(generated, kek.generated.size)
        assertEquals(deletes, kek.calls(KekOp.Delete))
    }

    @Test
    fun aKekFoundBrokenForAnOlderGeneration_isNeverReplaced() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val changed = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        failKekDecrypt(2)
        vault.unlockReportingKekBroken() // for generation 1
        vault.commit(2, changed.slot, null)
        failKekDecrypt(2)
        val generated = kek.generated.size
        val deletes = kek.calls(KekOp.Delete)

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.PASSWORD, password.copyOf()) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(generated, kek.generated.size)
        assertEquals(deletes, kek.calls(KekOp.Delete))
    }

    @Test
    fun eraseState_forgetsABrokenKek() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        failKekDecrypt(2)
        vault.unlockReportingKekBroken() // for generation 1
        vault.eraseState()
        vault.installWithSlot() // generation 1 again, on a new KEK
        failKekDecrypt(2)
        val deletes = kek.calls(KekOp.Delete)

        val failure = assertThrowsSuspending<KeyVaultException> { vault.prepare(KeyMode.PASSWORD, password.copyOf()) }

        assertEquals(KeyFailure.KeyUnavailable, failure.failure)
        assertEquals(deletes, kek.calls(KekOp.Delete))
    }

    @Test
    fun withAWorkingKek_prepareReusesIt() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        assertFalse((vault.unlockWithBiometric(vault.unlockCipher()) as UnlockResult.Key).kekBroken)
        val generated = kek.generated.size

        vault.prepare(KeyMode.PASSWORD, password.copyOf())

        assertEquals(generated, kek.generated.size)
        assertEquals(0, kek.calls(KekOp.Delete))
    }

    // --- deletion ---

    @Test
    fun deleteBiometricKey_deletesEveryBiometricKey_isIdempotent_andNeverTouchesTheFile() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        vault.armCipher() // an orphan next to the slot's key
        val before = rule.stateFile.readBytes()

        vault.deleteBiometricKey()
        vault.deleteBiometricKey()

        assertFalse(rule.bioKeyExists())
        assertArrayEquals(before, rule.stateFile.readBytes())
        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
    }

    @Test
    fun deleteUnreferencedBiometricKeys_keepsOnlyTheCommittedSlotsKey_andNeverTouchesTheFile() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val alias = vault.slotAlias()
        vault.armCipher() // the orphan of an arming that never committed
        val before = rule.stateFile.readBytes()

        vault.deleteUnreferencedBiometricKeys()

        assertEquals(setOf(alias), rule.bioAliases())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
        assertArrayEquals(before, rule.stateFile.readBytes())
    }

    @Test
    fun deleteUnreferencedBiometricKeys_withoutABioSlot_orWithoutAFile_deletesEveryKey() = runVaultTest {
        val vault = vault()
        vault.commit(1, vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot, null)
        vault.armCipher()

        vault.deleteUnreferencedBiometricKeys()
        assertFalse(rule.bioKeyExists())

        vault.armCipher()
        check(rule.stateFile.delete())

        vault.deleteUnreferencedBiometricKeys()
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun deleteUnreferencedBiometricKeys_overAnInvalidFile_deletesNothing() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        vault.armCipher()
        val aliases = rule.bioAliases()
        rule.stateFile.writeText("{")

        vault.deleteUnreferencedBiometricKeys()

        assertEquals(aliases, rule.bioAliases())
    }

    @Test
    fun aFailedKeyListing_isRetriedOnceAfterOneSecond() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val alias = vault.slotAlias()
        vault.armCipher()
        bio.failNext(BioOp.Aliases, KeyStoreException("injected"))
        val start = currentTime

        vault.deleteUnreferencedBiometricKeys()

        assertEquals(1_000L, currentTime - start)
        assertEquals(setOf(alias), rule.bioAliases())
    }

    /** A slot found dead outside the vault dies even when the Keystore cannot list its keys (§ 3.7.4). */
    @Test
    fun aKeyListingThatKeepsFailing_isNoError_andStillDeletesTheCommittedSlotsKey() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val alias = vault.slotAlias()
        val orphan = vault.armCipher().keyAlias
        repeat(2) { bio.failNext(BioOp.Aliases, KeyStoreException("injected")) }

        vault.deleteUnreferencedBiometricKeys()
        assertEquals(setOf(alias, orphan), rule.bioAliases()) // only the listing could name the orphan

        repeat(2) { bio.failNext(BioOp.Aliases, KeyStoreException("injected")) }
        vault.deleteBiometricKey()

        assertEquals(setOf(orphan), rule.bioAliases())
        assertEquals(BioSlotStatus.Dead, vault.biometricSlotStatus())
    }

    @Test
    fun aKeyListingBug_isACrash_notASkippedDeletion() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        bio.failNext(BioOp.Aliases, IllegalStateException("a bug"))

        assertThrowsSuspending<IllegalStateException> { vault.deleteUnreferencedBiometricKeys() }
    }

    @Test
    fun discardArmCipher_deletesOnlyThePromptsKey_overALiveSlot_anInvalidFile_orNone() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val alias = vault.slotAlias()
        val before = rule.stateFile.readBytes()

        vault.discardArmCipher(vault.armCipher())
        assertEquals(setOf(alias), rule.bioAliases())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
        assertArrayEquals(before, rule.stateFile.readBytes())

        // No file names a fresh alias that no commit wrote, so an invalid file keeps nothing alive but the slot's key.
        val overInvalid = vault.armCipher()
        rule.stateFile.writeText("{")
        vault.discardArmCipher(overInvalid)
        assertEquals(setOf(alias), rule.bioAliases())

        check(rule.stateFile.delete())
        vault.discardArmCipher(overInvalid) // idempotent, also without a file
        assertEquals(setOf(alias), rule.bioAliases())
    }

    @Test
    fun discardArmCipher_keepsTheKeyOfTheCommittedSlot() = runVaultTest {
        val vault = vault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)
        val cipher = vault.armCipher()
        vault.commit(1, prepared.slot, (vault.wrapForBiometric(cipher, prepared.rawKey) as WrapResult.Wrapped).bioSlot)

        vault.discardArmCipher(cipher) // a creation whose read-back failed after its write landed

        assertEquals(setOf(cipher.keyAlias), rule.bioAliases())
        assertEquals(BioSlotStatus.Live, vault.biometricSlotStatus())
    }

    @Test
    fun eraseState_deletesTheFileTheKekAndEveryBiometricKey() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        vault.armCipher()
        assertEquals(2, rule.bioAliases().size)

        vault.eraseState()

        assertFalse(rule.stateFile.exists())
        assertFalse(rule.kekExists())
        assertFalse(rule.bioKeyExists())
    }

    @Test
    fun eraseState_deletesTheCommittedSlotsKey_evenWhenTheKeystoreCannotListItsKeys() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val alias = vault.slotAlias()
        repeat(2) { bio.failNext(BioOp.Aliases, KeyStoreException("injected")) }

        vault.eraseState()

        assertFalse(alias in rule.bioAliases())
    }

    @Test
    fun aStaleBioSlotNextToANewerPasswordSlot_failsItsTag() = runVaultTest {
        val vault = vault()
        vault.installWithSlot()
        val stale = requireNotNull(requireNotNull(vault.readState()).bioSlot)
        val changed = vault.prepare(KeyMode.PASSWORD, password.copyOf())

        vault.commit(2, changed.slot, stale) // never written by DbMaintenance; the AAD binding catches it

        assertEquals(UnlockResult.SlotFailed(invalidated = true), vault.unlockWithBiometric(vault.unlockCipher()))
    }
}
