package com.doro.organizer.session

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.DoroAppRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.FakeKeyVault
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestProcessLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate part of L-03 (DEC-64): every password the gate receives is zeroed, the chars by `passwordBytes` or by
 * `dispatch` (T35), the bytes handed to `KeyVault.unlock` (E2) and `verifyPassword` (E4) by the effect, asserted
 * through a recording `FakeKeyVault` over a real holder and DbMaintenance.
 */
@RunWith(AndroidJUnit4::class)
class GatePasswordZeroingTest {
    private val vault = FakeKeyVault(DEK)
    private val settings = FakeSettingsStore().apply { load() }
    private val time = FakeAppTime()
    private val process = TestProcessLifecycle()

    @get:Rule(order = 0)
    val databases = TestDatabases()

    @get:Rule(order = 1)
    val app = DoroAppRule(autoStart = false) {
        settingsStore = settings
        appTime = time
        processLifecycle = process
        biometricGateway = FakeBiometricGateway()
        files = databases.files
        keyVault = { _, _, _ -> vault }
    }

    private val gate get() = app.container.sessionGate

    @Before
    fun passwordModeDatabase() {
        databases.createLive(1, TestKey.Raw(DEK))
        vault.commitMode(KeyMode.PASSWORD)
        onMain { process.moveTo(Lifecycle.State.RESUMED) }
    }

    @Test
    fun coldStartPassword_isZeroedByE2() = runBlocking {
        app.start()
        await { it is GateState.NeedsPassword }
        val chars = "correct horse".toCharArray()
        onMain { gate.dispatch(GateEvent.PasswordSubmitted(chars)) }
        await { it == GateState.Ready }
        assertZero(chars)
        assertTrue(vault.receivedPasswords.isNotEmpty())
        vault.receivedPasswords.forEach(::assertZero)
    }

    @Test
    fun passwordOverTheOpenDatabase_isZeroedByE4() = runBlocking {
        app.start()
        await { it is GateState.NeedsPassword }
        onMain { gate.dispatch(GateEvent.PasswordSubmitted("correct horse".toCharArray())) }
        await { it == GateState.Ready }
        settings.setBiometricLock(true)
        onMain { process.moveTo(Lifecycle.State.CREATED) } // LockNow: Locked(Ready)
        onMain { app.container.appLockController.prompt() } // no slot: the password entry (T46)
        await { it is GateState.Locked && it.password != null }
        val chars = "correct horse".toCharArray()
        onMain { gate.dispatch(GateEvent.PasswordSubmitted(chars)) }
        await { it == GateState.Ready }
        assertZero(chars)
        vault.receivedPasswords.forEach(::assertZero)
    }

    @Test
    fun aPasswordNoRowTakes_isZeroedByDispatch() = runBlocking {
        settings.setBackoff(5, time.elapsedMillis() + 30_000)
        app.start()
        await { it is GateState.NeedsPassword }
        val chars = "too early".toCharArray()
        onMain { gate.dispatch(GateEvent.PasswordSubmitted(chars)) } // the T7 guard fails: T35
        assertZero(chars)
        assertTrue(vault.receivedPasswords.isEmpty())
    }

    private suspend fun await(predicate: (GateState) -> Boolean) = withTimeout(20_000) { gate.state.first(predicate) }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun assertZero(chars: CharArray) = assertTrue("the chars are zeroed", chars.all { it == '\u0000' })

    private fun assertZero(bytes: ByteArray) = assertTrue("the bytes are zeroed", bytes.all { it == 0.toByte() })

    private companion object {
        val DEK = ByteArray(32) { (it * 3 + 1).toByte() }
    }
}
