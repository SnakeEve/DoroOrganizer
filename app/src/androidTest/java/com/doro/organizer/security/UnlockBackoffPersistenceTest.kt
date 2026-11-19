package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.PersistedSettings
import com.doro.organizer.testing.TestKdf
import com.doro.organizer.testing.testDispatchers
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The KeyVault part of testing.md L-02 `UnlockBackoffPersistenceTest` (DEC-62): the count and the retry time are in
 * DataStore before the answer, and a new vault over the same files is still throttled. The gate and dialog parts come
 * with SessionGate and DbMaintenance.
 */
@RunWith(AndroidJUnit4::class)
class UnlockBackoffPersistenceTest {
    @get:Rule
    val rule = KeyVaultRule()

    private val time = FakeAppTime()
    private val kdf = TestKdf(time)
    private val settings by lazy { PersistedSettings(rule) }
    private val password = "correct horse 42".toByteArray()
    private val wrongPassword = "wrong horse 42".toByteArray()

    @After
    fun release() = runBlocking { settings.closeAll() }

    @Test
    fun wrongPasswords_arePersistedBeforeTheAnswer_andANewVaultIsStillThrottled() = runTest(timeout = 60.seconds) {
        val vault = rule.vault(settings.open(), time, testDispatchers(), kdf = kdf)
        vault.prepare(KeyMode.PASSWORD, password.copyOf()).also { vault.commit(1, it.slot, null) }

        repeat(4) { vault.unlock(wrongPassword) }
        val fifth = vault.unlock(wrongPassword) as UnlockResult.PasswordRejected

        val stored = settings.reread()
        assertEquals(5, stored.unlockFailures)
        assertEquals(fifth.retryAtElapsedMs, stored.unlockRetryAtElapsedMs)
        val again = rule.vault(settings.open(), time, testDispatchers(), kdf = kdf)
        assertEquals(UnlockResult.Throttled(5, fifth.retryAtElapsedMs), again.unlock(password))
    }

    @Test
    fun aCorrectPassword_resetsThePersistedBackoff() = runTest(timeout = 60.seconds) {
        val vault = rule.vault(settings.open(), time, testDispatchers(), kdf = kdf)
        vault.prepare(KeyMode.PASSWORD, password.copyOf()).also { vault.commit(1, it.slot, null) }
        repeat(3) { vault.unlock(wrongPassword) }

        vault.unlock(password)

        val stored = settings.reread()
        assertEquals(0, stored.unlockFailures)
        assertEquals(0L, stored.unlockRetryAtElapsedMs)
    }
}
