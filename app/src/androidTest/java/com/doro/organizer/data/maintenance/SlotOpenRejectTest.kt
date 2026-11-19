package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.WrapResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-47, the fingerprint-reset cases (DEC-60, DEC-85; security.md § 4.3, § 3.7.3): a slot whose released
 * key does not fit the database is a slot failure, never the Corrupt screen. The cold-start case belongs to the gate.
 */
@RunWith(AndroidJUnit4::class)
class SlotOpenRejectTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private fun runRejectTest(body: suspend () -> Unit) = runTest(timeout = 120.seconds) { body() }

    /** PASSWORD mode whose biometric slot wraps another DEK than the database's: GCM-valid, but the wrong key. */
    private suspend fun slotWithAForeignKey() {
        setup.start()
        setup.changeKey(KeyMode.PASSWORD, new = PASSWORD)
        val state = setup.state()
        val foreign = TestKey.Raw(ByteArray(32) { 0x2C }).bytes()
        val bio = (setup.vault.wrapForBiometric(setup.armCipher(), foreign) as WrapResult.Wrapped).bioSlot
        setup.vault.commit(state.generation, state.slot, bio)
    }

    @Test
    fun fromNeedsPassword_aRejectedSlotKeyAtCheckSource_isAuthFailed_deletesTheAlias_andAborts() = runRejectTest {
        slotWithAForeignKey()
        setup.holder.close()
        val state = vaults.stateFile.readBytes()
        val failures = setup.settings.snapshot().unlockFailures

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.NeedsPassword, setup.unlockCipher(), NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.AuthFailed), result)
        assertFalse(vaults.bioKeyExists())
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(MaintenanceOutcome.Aborted, setup.listener.finished.last())
        assertEquals(failures, setup.settings.snapshot().unlockFailures)
        setup.start(PASSWORD) // the password path remains, and nothing was found corrupt
    }

    @Test
    fun fromReady_aSlotKeyThatDiffersFromTheLiveKey_isUnexpected_andChangesNothing() = runRejectTest {
        slotWithAForeignKey()
        val state = vaults.stateFile.readBytes()
        val started = setup.listener.started.size

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, setup.unlockCipher(), NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.Unexpected), result)
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(started, setup.listener.started.size)
        assertTrue(setup.holder.state.value is HolderState.Open)
        assertTrue(vaults.bioKeyExists())
    }

    private companion object {
        const val PASSWORD = "startup password 1"
        const val NEW = "the new password"
    }
}
