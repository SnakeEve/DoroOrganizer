package com.doro.organizer.data.maintenance

import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeKeyVault
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe (SPEC T22) for docs/db-lifecycle.md § Restore, § Steps, step 5 (`OPEN_INPUT`): "After opening:
 * `PRAGMA cell_size_check = ON` and `PRAGMA trusted_schema = OFF` on the candidate connection … SQLite ignores an
 * unknown pragma silently, so whether they take effect stays open until a test reads them back."
 *
 * The candidate is a real backup, written by the production `MaintenanceConnection.exportTo` into
 * `restore/incoming.db`, and it is opened by the production `RestoreCandidates.open` (`openChecked`), plaintext and
 * passphrase-keyed. On the connection that call returns, `PRAGMA cell_size_check` must read 1 and
 * `PRAGMA trusted_schema` must read 0. A pragma SQLite did not know would return no row, which fails as an empty
 * observation. So that the candidate's values can only come from `openChecked`, a fresh `QueryConnection` to the same
 * file that skips `openChecked` must first read the build's defaults, 0 and 1, the opposite values (a stated
 * precondition: with another default the probe could not tell the setting from the default). So that a read-back
 * which ignores the setting cannot pass, the test then sets the opposite values on the candidate connection and reads
 * 0 and 1. A failure states the observed rows, never user data (DEC-70); if the phone contradicts the docs, the
 * failure is the reported outcome and the expectation is not adjusted.
 */
@RunWith(AndroidJUnit4::class)
class CandidatePragmasTest {
    @get:Rule
    val databases = TestDatabases()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun plaintextCandidate_readsCellSizeCheckOn_andTrustedSchemaOff() = checkCandidate(TestKey.Plain, encrypted = false)

    @Test
    fun encryptedCandidate_readsCellSizeCheckOn_andTrustedSchemaOff() =
        checkCandidate(TestKey.Passphrase(BACKUP_PASSWORD), encrypted = true)

    private fun checkCandidate(backupKey: TestKey, encrypted: Boolean): Unit = runBlocking {
        val deps = MaintenanceDeps(
            context, databases.files, FakeKeyVault(), databases.newHolder(), FakeSettingsStore(), FakeAppTime(),
            MaintenanceFaultHook.None,
        )
        deps.files.ensureDirs()
        val questions = RestoreQuestions().apply { begin() }
        val candidates = RestoreCandidates(deps, questions)
        writeBackup(candidates.incoming, backupKey)
        val defaults = defaultPragmas(candidates.incoming, backupKey)
        assertEquals(
            "precondition: PRAGMA cell_size_check and trusted_schema on a fresh connection without openChecked",
            listOf(listOf<String?>("0"), listOf<String?>("1")),
            defaults,
        )

        val passwordQuestions = mutableListOf<Boolean>()
        val run = OperationRun(
            MaintenanceOp.Restore,
            MaintenanceFaultHook.None,
            publish = { status ->
                if (status is MaintenanceStatus.NeedsBackupPassword) {
                    passwordQuestions += status.retry
                    // A retry means the password was rejected: cancel rather than ask forever.
                    val chars = if (status.retry) null else BACKUP_PASSWORD.toCharArray()
                    questions.answer(RestoreQuestions.Answer.Password(chars))
                }
            },
            raiseStarted = { error("a restore raises nothing before its confirmation") },
        )
        try {
            val candidate = candidates.open(run, CancellationSignal())
            candidate.connection.use { connection ->
                assertEquals("candidate encrypted", encrypted, candidate.encrypted)
                val expectedQuestions = if (encrypted) listOf(false) else emptyList()
                assertEquals("password questions (retry flags)", expectedQuestions, passwordQuestions)

                assertEquals(
                    "PRAGMA cell_size_check on the candidate connection after openChecked",
                    listOf(listOf<String?>("1")),
                    connection.rows("PRAGMA cell_size_check"),
                )
                assertEquals(
                    "PRAGMA trusted_schema on the candidate connection after openChecked",
                    listOf(listOf<String?>("0")),
                    connection.rows("PRAGMA trusted_schema"),
                )

                // The read-back follows the setting: the opposite values read back as set.
                connection.exec("PRAGMA cell_size_check = OFF")
                connection.exec("PRAGMA trusted_schema = ON")
                assertEquals(
                    "PRAGMA cell_size_check after setting it OFF on the same connection",
                    listOf(listOf<String?>("0")),
                    connection.rows("PRAGMA cell_size_check"),
                )
                assertEquals(
                    "PRAGMA trusted_schema after setting it ON on the same connection",
                    listOf(listOf<String?>("1")),
                    connection.rows("PRAGMA trusted_schema"),
                )
            }
        } finally {
            run.zeroSecrets()
            questions.end()
        }
    }

    /** `cell_size_check` and `trusted_schema` on a fresh production connection to [file] that skips `openChecked`. */
    private fun defaultPragmas(file: File, key: TestKey): List<List<String?>> {
        val bytes = key.bytes()
        try {
            return QueryConnection.open(file, bytes, null).use { connection ->
                connection.rows("PRAGMA cell_size_check") + connection.rows("PRAGMA trusted_schema")
            }
        } finally {
            bytes.fill(0)
        }
    }

    /** A backup of an empty live database, exported by production into [target] and keyed with [key]. */
    private fun writeBackup(target: File, key: TestKey) {
        val live = databases.createLive(key = TestKey.Raw(LIVE_DEK))
        val liveBytes = TestKey.Raw(LIVE_DEK).bytes()
        val targetBytes = key.bytes()
        try {
            MaintenanceConnection.open(live, liveBytes).use { it.exportTo(target, targetBytes) }
        } finally {
            liveBytes.fill(0)
            targetBytes.fill(0)
        }
    }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 31"
        val LIVE_DEK = ByteArray(32) { (it * 9 + 2).toByte() }
    }
}
