package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.PersistedSettings
import com.doro.organizer.testing.TestKdf
import com.doro.organizer.testing.testDispatchers
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The KeyVault part of testing.md L-37 `PasswordReminderPersistenceTest` (DEC-74): the real vault writes the
 * timestamp next to the backoff values before it answers, a new store reads the same value, and a wrong or throttled
 * password does not restart it. The gate, dialog and fingerprint parts come with their components.
 */
@RunWith(AndroidJUnit4::class)
class PasswordReminderPersistenceTest {
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
    fun committingANewPassword_andEachCorrectEntry_persistTheTimestamp() = runTest(timeout = 60.seconds) {
        val vault = rule.vault(settings.open(), time, testDispatchers(), kdf = kdf)
        vault.prepare(KeyMode.PASSWORD, password.copyOf()).also { vault.commit(1, it.slot, null) }
        val committedAt = time.nowMillis()
        assertEquals(committedAt, settings.reread().passwordVerifiedAt)

        time.advanceBy(Duration.ofDays(3))
        val reopened = rule.vault(settings.open(), time, testDispatchers(), kdf = kdf)
        reopened.verifyPassword(password)

        assertEquals(time.nowMillis(), settings.reread().passwordVerifiedAt)
    }

    @Test
    fun wrongAndThrottledPasswords_doNotRestartIt() = runTest(timeout = 60.seconds) {
        val vault = rule.vault(settings.open(), time, testDispatchers(), kdf = kdf)
        vault.prepare(KeyMode.PASSWORD, password.copyOf()).also { vault.commit(1, it.slot, null) }
        val committedAt = time.nowMillis()
        time.advanceBy(Duration.ofDays(1))

        repeat(5) { vault.unlock(wrongPassword) }
        vault.unlock(password) // throttled

        assertEquals(committedAt, settings.reread().passwordVerifiedAt)
        assertFalse(rule.vault(settings.open(), time, testDispatchers(), kdf = kdf).isPasswordReminderDue())
    }
}
