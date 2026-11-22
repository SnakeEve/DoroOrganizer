package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.PREVIEW_EXPR
import com.doro.organizer.data.db.RestoreChecks
import com.doro.organizer.debug.RecordingDocumentsProvider
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer
import com.doro.organizer.testing.RestoreAnswer.Companion.backupPassword
import com.doro.organizer.testing.RestoreAnswer.Companion.decline
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDocument
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.backupTo
import com.doro.organizer.testing.corruptPage
import com.doro.organizer.testing.flipByteInPage
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.pageCount
import com.doro.organizer.testing.pageSize
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.restoreAnswering
import com.doro.organizer.testing.seed
import com.doro.organizer.testing.stringsOf
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-11 (DEC-84, db-lifecycle.md § Restore validation): one case per check and per trailing row, each
 * ending with the `BackupRejection` or error the table names, the live data and `db-state.json` unchanged, nothing
 * staged left behind and no gate event. Every invalid input is a mutation of a fresh valid backup made in the test.
 * The ui.md § 8.7 message of each category is the Compose half (SPEC T19). Not here: "a failed migration", which needs
 * a schema version older than `AppDatabase.VERSION` (1 has none).
 */
@RunWith(AndroidJUnit4::class)
class RestoreValidationTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private lateinit var before: Snapshot

    private fun runValidationTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 21, notes = 30).generate())
        before = snapshot()
        body()
    }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun shorterThan512Bytes_andEmpty_areNotABackup() = runValidationTest {
        assertRejected(document("short.db", Random(1).nextBytes(511)), BackupRejection.NotABackup)
        assertRejected(document("empty.db", ByteArray(0)), BackupRejection.NotABackup)
    }

    @Test
    fun aPlaintextHeaderWithABadPageSize_isDamaged() = runValidationTest {
        val backup = setup.backupTo(context, "bad-page-size.db")
        RandomAccessFile(backup.file, "rw").use { f ->
            f.seek(16)
            f.write(byteArrayOf(0x0B, 0xB8.toByte())) // 3000: not a power of two
        }

        assertRejected(backup, BackupRejection.Damaged)
    }

    @Test
    fun noHeaderAndALengthThatIsNoMultipleOf4096_isNotABackup_withoutAPasswordQuestion() = runValidationTest {
        assertRejected(document("odd.db", Random(2).nextBytes(5000)), BackupRejection.NotABackup)
    }

    @Test
    fun randomBytes_askForAPassword_repeatAfterEveryAnswer_andCancel() = runValidationTest {
        val random = document("random.db", Random(3).nextBytes(8192))

        val result = setup.maintenance.restoreAnswering(
            random.uri,
            MaintenanceOrigin.Ready,
            backupPassword("any password"),
            backupPassword("another password", retry = true),
            backupPassword(null, retry = true),
        )

        assertEquals(AppResult.Ok(MaintenanceResult.RestoreCancelled), result)
        assertUnchanged("random bytes")
    }

    @Test
    fun aWrongPasswordOfARealBackup_isNoRejection_andTheQuestionIsCancellable() = runValidationTest {
        val backup = setup.backupTo(context, "encrypted.db", BACKUP_PASSWORD)

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.Ready,
            backupPassword("wrong password"),
            backupPassword(null, retry = true),
        )

        assertEquals(AppResult.Ok(MaintenanceResult.RestoreCancelled), result)
        assertUnchanged("wrong password")
    }

    @Test
    fun truncatedPlainAndEncryptedBackups_areDamaged() = runValidationTest {
        val plain = setup.backupTo(context, "truncated-plain.db")
        truncateToHalf(plain)
        assertRejected(plain, BackupRejection.Damaged)

        val encrypted = setup.backupTo(context, "truncated-encrypted.db", BACKUP_PASSWORD)
        truncateToHalf(encrypted)
        assertRejected(encrypted, BackupRejection.Damaged, backupPassword(BACKUP_PASSWORD))
    }

    @Test
    fun aDatabaseWithoutSchemaObjects_andAForeignSqliteFile_areNotABackup() = runValidationTest {
        val empty = TestDocument.named(context, "no-objects.db")
        databases.createRaw(empty.file, TestKey.Plain).apply {
            execSQL("PRAGMA user_version = 1") // writes page 1 without any object
            close()
        }
        assertRejected(empty, BackupRejection.NotABackup)

        val foreign = TestDocument.named(context, "foreign.db")
        databases.createRaw(foreign.file, TestKey.Plain).apply {
            execSQL("CREATE TABLE contacts (id INTEGER PRIMARY KEY, name TEXT)")
            close()
        }
        assertRejected(foreign, BackupRejection.NotABackup)
    }

    @Test
    fun theDoroSchemaWithApplicationId0_isNotABackup() = runValidationTest {
        assertRejected(mutated("application-id.db") { execSQL("PRAGMA application_id = 0") }, BackupRejection.NotABackup)
    }

    @Test
    fun aNewerUserVersion_isNewerVersion_andOneBelow1_isIncompatible() = runValidationTest {
        val newer = mutated("newer.db") { execSQL("PRAGMA user_version = ${AppDatabase.VERSION + 1}") }
        assertRejected(newer, BackupRejection.NewerVersion)

        assertRejected(mutated("version-0.db") { execSQL("PRAGMA user_version = 0") }, BackupRejection.Incompatible)
    }

    @Test
    fun anIdentityHashMismatchAtTheSameVersion_isIncompatible() = runValidationTest {
        val other = mutated("identity.db") { execSQL("UPDATE room_master_table SET identity_hash = 'another schema'") }

        assertRejected(other, BackupRejection.Incompatible)
    }

    @Test
    fun anIntegrityCheckFailure_isDamaged() = runValidationTest {
        val backup = setup.backupTo(context, "integrity.db")
        val clean = databases.raw(backup.file, TestKey.Plain).use { it.answersBeforeCheck12() }
        // A page whose damage only check 12 sees: everything the restore reads before it (the open's checks 6-7,
        // checks 8-9, check 11's identity hash) still reads as in the clean backup, and integrity_check reports the
        // damage as rows. Not every page qualifies: damage that breaks those reads, or the table scans integrity_check
        // runs after its b-tree walk, throws SQLITE_CORRUPT, which is the generic SQLiteException row of the table
        // (or a garbled hash, Incompatible) and never reaches check 12's comparison.
        val probe = databases.file("integrity-probe.db")
        val page = (pageCount(backup.file) downTo 2).firstOrNull { page ->
            backup.file.copyTo(probe, overwrite = true)
            corruptPage(probe, page)
            try {
                databases.raw(probe, TestKey.Plain).use { raw ->
                    raw.answersBeforeCheck12() == clean && raw.stringsOf("PRAGMA integrity_check") != listOf("ok")
                }
            } catch (_: SQLiteDatabaseCorruptException) {
                false
            }
        }
        probe.delete()
        corruptPage(backup.file, checkNotNull(page) { "no page whose damage only integrity_check reports" })
        // Only check 12 can see it.
        databases.raw(backup.file, TestKey.Plain).use { raw ->
            assertEquals(clean, raw.answersBeforeCheck12())
            assertNotEquals(listOf("ok"), raw.stringsOf("PRAGMA integrity_check"))
        }

        assertRejected(backup, BackupRejection.Damaged)
    }

    @Test
    fun aCipherIntegrityFailureThatIntegrityCheckCannotSee_isDamaged() = runValidationTest {
        val backup = setup.backupTo(context, "cipher-integrity.db", BACKUP_PASSWORD)
        val key = TestKey.Passphrase(BACKUP_PASSWORD)
        // Free pages: a scratch table's pages go to the freelist when it is dropped; the schema stays the backup's.
        databases.raw(backup.file, key).use { raw ->
            raw.execSQL("CREATE TABLE scratch (x BLOB)")
            raw.execSQL("INSERT INTO scratch VALUES (randomblob(200000))")
            raw.execSQL("DROP TABLE scratch")
            assertTrue(raw.longOf("PRAGMA freelist_count") > 1)
        }
        // A freelist leaf page is never read by integrity_check (check 12); cipher_integrity_check reads every page.
        val probe = databases.file("cipher-integrity-probe.db")
        val page = (pageCount(backup.file) downTo 2).firstOrNull { page ->
            backup.file.copyTo(probe, overwrite = true)
            flipByteInPage(probe, page)
            databases.raw(probe, key).use { raw ->
                raw.stringsOf("PRAGMA integrity_check") == listOf("ok") && raw.stringsOf("PRAGMA cipher_integrity_check").isNotEmpty()
            }
        }
        probe.delete()
        flipByteInPage(backup.file, checkNotNull(page) { "no page that only cipher_integrity_check reads" })

        assertRejected(backup, BackupRejection.Damaged, backupPassword(BACKUP_PASSWORD))
    }

    @Test
    fun aForeignKeyViolation_isDamaged() = runValidationTest {
        val dangling = mutated("foreign-key.db") { execSQL("INSERT INTO note_persons (note_id, person_id) VALUES (999999, 999999)") }

        assertRejected(dangling, BackupRejection.Damaged)
    }

    @Test
    fun aMissingTable_andAnExtraTriggerOrView_areIncompatible() = runValidationTest {
        assertRejected(mutated("missing-table.db") { execSQL("DROP TABLE note_projects") }, BackupRejection.Incompatible)
        val trigger = mutated("trigger.db") { execSQL("CREATE TRIGGER extra AFTER INSERT ON notes BEGIN SELECT 1; END") }
        assertRejected(trigger, BackupRejection.Incompatible)
        assertRejected(mutated("view.db") { execSQL("CREATE VIEW extra AS SELECT id FROM notes") }, BackupRejection.Incompatible)
    }

    /**
     * One case per restore check (L-11). A case that changes `content` also stores the matching preview, so it breaks
     * only its own check and not R-6 (INV-13, DEC-106).
     */
    @Test
    fun eachRestoreCheck_isDamaged() = runValidationTest {
        val first = "(SELECT min(id) FROM notes)"
        val matchingPreview = "UPDATE notes SET preview = $PREVIEW_EXPR WHERE id = $first"
        val cases = mapOf(
            "R-1 checked without checked_at" to listOf("UPDATE notes SET checked = 1, checked_at = NULL WHERE id = $first"),
            "R-2 600,003 content bytes" to
                listOf("UPDATE notes SET content = '${"€".repeat(200_001)}' WHERE id = $first", matchingPreview),
            "R-3 after 2100-12-31" to listOf("UPDATE notes SET date = 47847 WHERE id = $first"),
            "R-3 before 1900-01-01" to listOf("UPDATE notes SET date = -25568 WHERE id = $first"),
            "R-4 BLOB content" to listOf("UPDATE notes SET content = X'00' WHERE id = $first", matchingPreview),
            "R-4 BLOB preview" to listOf("UPDATE notes SET preview = X'00' WHERE id = $first"),
            "R-5 201-character name" to
                listOf("UPDATE persons SET name = '${"€".repeat(201)}' WHERE id = (SELECT min(id) FROM persons)"),
            "R-6 a preview that is not its content's" to
                listOf("UPDATE notes SET preview = 'not the preview of ' || preview WHERE id = $first"),
        )
        for ((index, case) in cases.keys.withIndex()) {
            val document = mutated("r-check-$index.db") { cases.getValue(case).forEach { execSQL(it) } }
            assertRejected(document, BackupRejection.Damaged, case = case)
        }
    }

    /**
     * R-6 alone rejects a stored preview that disagrees with its content (INV-13, DEC-106): the candidate passes every
     * other restore check of its version, so without R-6 nothing would reject it.
     */
    @Test
    fun aStoredPreviewThatDisagreesWithItsContent_isDamaged_byR6() = runValidationTest {
        val first = "(SELECT min(id) FROM notes)"
        val forged = mutated("r6-forged.db") {
            execSQL("UPDATE notes SET preview = 'not the preview of ' || preview WHERE id = $first")
            val r6 = "SELECT COUNT(*) FROM notes WHERE preview IS NOT $PREVIEW_EXPR"
            val checks = RestoreChecks.forVersion(AppDatabase.VERSION)
            assertTrue("R-6 is a restore check", r6 in checks)
            assertEquals(1L, longOf(r6))
            checks.filter { it != r6 }.forEach { check -> assertEquals(check, 0L, longOf(check)) }
        }
        assertRejected(forged, BackupRejection.Damaged, case = "R-6")
    }

    /**
     * A BLOB preview is counted by R-4 (its type) and by R-6 (a BLOB never equals the text of [PREVIEW_EXPR]); every
     * other check passes on it. The rejection alone cannot show R-4's `preview` term, because R-6 rejects the file
     * too, so the count of each check is asserted on the forged file (L-11).
     */
    @Test
    fun aBlobPreview_isCountedByR4AndR6_andIsDamaged() = runValidationTest {
        val forged = mutated("r4-blob-preview.db") {
            execSQL("UPDATE notes SET preview = X'00' WHERE id = (SELECT min(id) FROM notes)")
            val counts = RestoreChecks.forVersion(AppDatabase.VERSION).map { longOf(it) }
            assertEquals("R-1 to R-6", listOf(0L, 0L, 0L, 1L, 0L, 1L), counts)
        }
        assertRejected(forged, BackupRejection.Damaged, case = "R-4 and R-6")
    }

    @Test
    fun schemaStructureChangesBehindACopiedIdentityHash_areIncompatible() = runValidationTest {
        assertRejected(mutated("extra-column.db") { execSQL("ALTER TABLE notes ADD COLUMN extra TEXT") }, BackupRejection.Incompatible)
        val missingNotNull = mutated("missing-not-null.db") { rebuildPersons("`name` TEXT") }
        assertRejected(missingNotNull, BackupRejection.Incompatible)
        val addedCheck = mutated("added-check.db") { rebuildPersons("`name` TEXT NOT NULL CHECK (length(`name`) > 0)") }
        assertRejected(addedCheck, BackupRejection.Incompatible)
    }

    @Test
    fun aPlaintextHeaderWithSchemaFormat5_isDamaged() = runValidationTest {
        val backup = setup.backupTo(context, "schema-format.db")
        RandomAccessFile(backup.file, "rw").use { f ->
            f.seek(44) // the schema format number, a big-endian 32-bit integer
            f.writeInt(5)
        }

        assertRejected(backup, BackupRejection.Damaged)
    }

    @Test
    fun aStreamDeliveringMoreThanTheReportedSize_isDamaged() = runValidationTest {
        val backup = setup.backupTo(context, "longer.db")
        RecordingDocumentsProvider.reportedSize = backup.file.length() - pageSize(backup.file)

        assertRejected(backup, BackupRejection.Damaged)
    }

    @Test
    fun aReportedSizeBeyondThreeTimesTheFreeSpace_isStorageFull_beforeAnythingIsCopied() = runValidationTest {
        val backup = setup.backupTo(context, "huge.db")
        synchronized(RecordingDocumentsProvider.modes) { RecordingDocumentsProvider.modes.clear() } // the backup's write
        RecordingDocumentsProvider.reportedSize = setup.files.noBackupDir.usableSpace / 3 + 1

        val result = setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready)

        assertEquals(AppResult.Err(AppError.StorageFull), result)
        assertTrue(RecordingDocumentsProvider.modes.isEmpty()) // never opened
        assertUnchanged("precheck")
    }

    @Test
    fun aStreamEndingBeforeTheReportedSize_isFileAccess_notADamagedBackup() = runValidationTest {
        val backup = setup.backupTo(context, "short-stream.db")
        RecordingDocumentsProvider.reportedSize = backup.file.length() + pageSize(backup.file)

        val result = setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready)

        assertEquals(AppResult.Err(AppError.FileAccess), result)
        assertUnchanged("short stream")
    }

    @Test
    fun aSizeThatIsUnreported_0OrNegative_isUnknown_andTheBackupRestores() = runValidationTest {
        val backup = setup.backupTo(context, "unknown-size.db")
        val cases = listOf<Pair<String, () -> Unit>>(
            "no SIZE" to { RecordingDocumentsProvider.sizeUnknown = true },
            "SIZE 0" to { RecordingDocumentsProvider.reportedSize = 0 },
            "SIZE -1" to { RecordingDocumentsProvider.reportedSize = -1 },
        )
        for ((case, report) in cases) {
            RecordingDocumentsProvider.sizeUnknown = false
            report()

            val result = setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready, RestoreAnswer.confirm())

            assertTrue("$case $result", (result as AppResult.Ok).value is MaintenanceResult.Restored)
            assertEquals(case, before.content, setup.dump()) // a backup of the live data
        }
    }

    @Test
    fun aReferenceDatabaseThatCannotBeCreated_isClassifiedAsDbMaintenancesOwnFailure_notTheBackups() = runValidationTest {
        val backup = setup.backupTo(context, "reference.db")
        setup.hook.blockAt(MaintenanceOp.Restore, PipelineStep.VALIDATE_INPUT)

        val result = coroutineScope {
            val restore = async { setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready) }
            setup.hook.blocked.await()
            check(File(setup.files.restoreDir, "reference.db").mkdir()) // where check 10 creates its database
            setup.hook.release()
            restore.await()
        }

        assertEquals(AppResult.Err(AppError.Unexpected), result) // a programming fault after the rollback
        assertUnchanged("reference")
    }

    @Test
    fun cancelDuringTheCopy_closesTheInput_andEndsRestoreCancelled() = runValidationTest {
        val backup = setup.backupTo(context, "cancel-copy.db")
        setup.hook.blockAt(MaintenanceOp.Restore, PipelineStep.COPY_INPUT)

        val result = coroutineScope {
            val restore = async { setup.maintenance.restore(backup.uri, MaintenanceOrigin.Ready) }
            setup.hook.blocked.await()
            setup.maintenance.cancelRestore()
            setup.hook.release() // the descriptor opens after the cancel: it is closed at once, the read fails
            restore.await()
        }

        assertEquals(AppResult.Ok(MaintenanceResult.RestoreCancelled), result)
        assertUnchanged("cancelled while copying")
    }

    @Test
    fun declinedConfirmation_andCancelWhileChecking_endRestoreCancelled() = runValidationTest {
        val backup = setup.backupTo(context, "valid.db")

        assertEquals(
            AppResult.Ok(MaintenanceResult.RestoreCancelled),
            setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready, decline()),
        )
        assertUnchanged("declined")

        setup.hook.blockAt(MaintenanceOp.Restore, PipelineStep.VALIDATE_INPUT)
        val result = coroutineScope {
            val restore = async { setup.maintenance.restore(backup.uri, MaintenanceOrigin.Ready) }
            setup.hook.blocked.await()
            setup.maintenance.cancelRestore()
            setup.hook.release()
            restore.await()
        }
        assertEquals(AppResult.Ok(MaintenanceResult.RestoreCancelled), result)
        assertUnchanged("cancelled while checking")
    }

    private class Snapshot(val state: ByteArray, val generations: Set<Long>, val content: Any)

    private fun snapshot() = Snapshot(vaults.stateFile.readBytes(), setup.files.generationsOnDisk().keys, setup.dump())

    private suspend fun assertRejected(
        document: TestDocument,
        reason: BackupRejection,
        vararg answers: RestoreAnswer,
        case: String = document.toString(),
    ) {
        val result = setup.maintenance.restoreAnswering(document.uri, MaintenanceOrigin.Ready, *answers)

        assertEquals(case, AppResult.Err(AppError.InvalidBackup(reason)), result)
        assertUnchanged(case)
    }

    /** The live data, `db-state.json` and the generations are as before; nothing staged remains; no gate event. */
    private fun assertUnchanged(case: String) {
        assertArrayEquals(case, before.state, vaults.stateFile.readBytes())
        assertEquals(case, before.generations, setup.files.generationsOnDisk().keys)
        assertEquals(case, before.content, setup.dump())
        assertTrue(case, setup.holder.state.value is HolderState.Open)
        assertEquals(case, emptyList<String>(), setup.files.restoreDir.list()!!.toList())
        assertEquals(case, emptyList<String>(), setup.files.stagingDir.list()!!.toList())
        assertEquals(case, emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.Restore))
    }

    private fun document(name: String, content: ByteArray) =
        TestDocument.named(context, name).apply { file.writeBytes(content) }

    /** A fresh plaintext backup named [name], changed by [mutation] on a raw connection that is closed afterwards. */
    private suspend fun mutated(name: String, mutation: SQLiteDatabase.() -> Unit): TestDocument {
        val backup = setup.backupTo(context, name)
        databases.raw(backup.file, TestKey.Plain).apply {
            mutation()
            close()
        }
        return backup
    }

    /** Keeps the first half of [document]'s pages: a backup cut off at a page boundary. */
    private fun truncateToHalf(document: TestDocument) {
        val size = pageSize(document.file)
        RandomAccessFile(document.file, "rw").use { it.setLength(pageCount(document.file) / 2L * size) }
    }

    /** Rebuilds `persons` with [nameColumn] in place of `name TEXT NOT NULL`; every row and the identity hash stay. */
    private fun SQLiteDatabase.rebuildPersons(nameColumn: String) {
        execSQL(
            "CREATE TABLE persons_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, $nameColumn, `role` TEXT, " +
                "`notes` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
        )
        execSQL("INSERT INTO persons_new SELECT id, name, role, notes, created_at, updated_at FROM persons")
        execSQL("DROP TABLE persons") // raw connections run with foreign keys off: the junction rows stay
        execSQL("ALTER TABLE persons_new RENAME TO persons")
    }

    /**
     * What the restore reads from a candidate before check 12, on a connection set up as its open sets one up: the
     * schema count of checks 6-7, `application_id` and `user_version` (checks 8-9) and the identity hash (check 11).
     */
    private fun SQLiteDatabase.answersBeforeCheck12(): List<Any?> {
        execSQL("PRAGMA cell_size_check = ON")
        execSQL("PRAGMA trusted_schema = OFF")
        return listOf(
            longOf("SELECT count(*) FROM sqlite_master WHERE name <> 'android_metadata'"),
            longOf("PRAGMA application_id"),
            longOf("PRAGMA user_version"),
            stringsOf("SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1"),
        )
    }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 11"
    }
}
