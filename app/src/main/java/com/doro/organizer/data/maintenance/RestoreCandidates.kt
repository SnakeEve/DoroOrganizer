package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.data.buildRoom
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.isStorageFull
import java.io.File
import java.io.RandomAccessFile
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * The restore candidate `restore/incoming.db` from `OPEN_INPUT` through `CHECK_SCHEMA` (db-lifecycle.md § Restore
 * steps 5–8, § Restore validation checks 2–17). Only this private copy is ever opened, and only with fixed statements.
 */
internal class RestoreCandidates(private val deps: MaintenanceDeps, private val questions: RestoreQuestions) {
    /** The private copy of the picked document (step 4). */
    val incoming get() = File(deps.files.restoreDir, "incoming.db")
    private val reference get() = File(deps.files.restoreDir, "reference.db")

    /** The open candidate with its key ([encrypted]: the backup password's bytes, owned by the frame). */
    class Candidate(val connection: QueryConnection, val key: ByteArray, val encrypted: Boolean) {
        override fun toString() = "Candidate(encrypted=$encrypted)"
    }

    /** What the confirmation states, counted after the migration (step 8). */
    class Counts(val notes: Int, val persons: Int, val projects: Int) {
        override fun toString() = "Counts"
    }

    /** OPEN_INPUT (step 5, checks 2–7). */
    suspend fun open(run: OperationRun, signal: CancellationSignal): Candidate {
        questions.step(run, PipelineStep.OPEN_INPUT)
        val header = ByteArray(minOf(incoming.length(), CANDIDATE_HEADER_BYTES.toLong()).toInt())
        RandomAccessFile(incoming, "r").use { it.readFully(header) }
        return when (val verdict = candidateHeader(incoming.length(), header)) {
            is CandidateHeader.Rejected -> reject(verdict.reason)
            CandidateHeader.Plaintext -> Candidate(fromCandidate { openChecked(ByteArray(0), signal) }, ByteArray(0), false)
            CandidateHeader.Encrypted -> openEncrypted(run, signal)
        }
    }

    /**
     * VALIDATE_INPUT, MIGRATE_INPUT and CHECK_SCHEMA (steps 6–8, checks 8–17); returns the preview's counts. Closes
     * the candidate connection, which the export no longer needs.
     */
    suspend fun validate(run: OperationRun, candidate: Candidate, signal: CancellationSignal): Counts {
        var connection: QueryConnection? = candidate.connection
        try {
            questions.step(run, PipelineStep.VALIDATE_INPUT)
            val version = fromCandidate { checkNotNull(connection).checkIdentity() }
            val schema = referenceSchema()
            fromCandidate { checkNotNull(connection).checkContent(version, candidate.encrypted, schema) }
            val migrated = version < AppDatabase.VERSION
            if (migrated) {
                questions.step(run, PipelineStep.MIGRATE_INPUT)
                checkNotNull(connection).close()
                connection = null
                migrate(candidate.key)
                connection = fromCandidate { openChecked(candidate.key, signal) }
            }
            questions.step(run, PipelineStep.CHECK_SCHEMA)
            val checked = checkNotNull(connection)
            return fromCandidate {
                checked.checkSchema(schema, migrated)
                val count = { sql: String -> checked.long(sql).toInt() }
                Counts(count(COUNT_NOTES), count(COUNT_PERSONS), count(COUNT_PROJECTS))
            }
        } finally {
            connection?.close()
        }
    }

    /** Check 5: the backup password, asked until SQLCipher accepts it; unlimited and never throttled (security.md § 5.3). */
    private suspend fun openEncrypted(run: OperationRun, signal: CancellationSignal): Candidate {
        var retry = false
        while (true) {
            val chars = questions.password { run.show(MaintenanceStatus.NeedsBackupPassword(retry)) }
            run.show(MaintenanceStatus.Running(MaintenanceOp.Restore, PipelineStep.OPEN_INPUT))
            // A malformed password cannot have keyed any backup.
            val key = passwordBytes(chars)?.let(run::own)
            if (key != null) {
                val connection = try {
                    fromCandidate { openChecked(key, signal) }
                } catch (e: CandidateRejected) {
                    if (e.cause !is SQLiteNotADatabaseException) throw e
                    null // SQLITE_NOTADB at keying: a wrong password, or not a backup at all
                }
                if (connection != null) return Candidate(connection, key, encrypted = true)
                key.fill(0)
            }
            retry = true
        }
    }

    /** Opens the copy with [key] and runs checks 5 or 6 and 7, with the precautions for a file of unknown origin. */
    private fun openChecked(key: ByteArray, signal: CancellationSignal): QueryConnection {
        val connection = QueryConnection.open(incoming, key, signal)
        try {
            connection.long("SELECT count(*) FROM sqlite_master")
            connection.exec("PRAGMA cell_size_check = ON")
            connection.exec("PRAGMA trusted_schema = OFF")
            connection.checkNotEmpty()
            return connection
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
    }

    /**
     * Check 10: an empty database of this build at `restore/reference.db` gives the expected identity hash and schema
     * object set; it is deleted right after. Its failures are DbMaintenance's own, never the backup's.
     */
    private fun referenceSchema(): ReferenceSchema {
        createEmptyDatabase(deps.context, reference)
        try {
            return QueryConnection.open(reference, ByteArray(0), null).use { it.referenceSchema() }
        } finally {
            SQLiteDatabase.deleteDatabase(reference)
        }
    }

    /**
     * Step 7: every migration in one transaction, then Room's schema validation, on a temporary Room instance over the
     * copy (DEC-88). A missing migration path or a failed validation is `Incompatible`; a failing statement is the
     * file's (`Damaged`).
     */
    private fun migrate(key: ByteArray) {
        try {
            val db = buildRoom(deps.context, incoming, key, temporary = true)
            try {
                db.openHelper.writableDatabase
            } finally {
                db.close()
            }
        } catch (e: IllegalStateException) {
            reject(BackupRejection.Incompatible)
        } catch (e: SQLiteException) {
            if (e.isStorageFull()) throw e
            throw CandidateRejected(BackupRejection.Damaged, e)
        }
    }

    /**
     * The restore-table row "OPEN_INPUT through CHECK_SCHEMA": an `SQLiteException` from the candidate is the file's
     * (`Damaged`), except storage full, which the frame classifies.
     */
    private inline fun <T> fromCandidate(block: () -> T): T = try {
        block()
    } catch (e: SQLiteException) {
        if (e.isStorageFull()) throw e
        throw CandidateRejected(BackupRejection.Damaged, e)
    }

    override fun toString() = "RestoreCandidates"

    private companion object {
        // Check 15 has proven these tables exist; their names are fixed, never read from the candidate.
        const val COUNT_NOTES = "SELECT count(*) FROM notes"
        const val COUNT_PERSONS = "SELECT count(*) FROM persons"
        const val COUNT_PROJECTS = "SELECT count(*) FROM projects"
    }
}
