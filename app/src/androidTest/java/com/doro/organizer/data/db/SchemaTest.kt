package com.doro.organizer.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.core.content.contentValuesOf
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.OpenResult
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.longsOf
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.stringsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The version 1 schema on SQLCipher through the production Room builder (data-layer.md § Entities): testing.md I-02
 * (index names, `user_version`, `application_id`, no id reuse), I-03 (foreign keys) and I-04 (one engine). Rows are
 * written with bound SQL on Room's connection, independent of the DAOs.
 */
@RunWith(AndroidJUnit4::class)
class SchemaTest {
    @get:Rule
    val databases = TestDatabases()

    private val now = FakeAppTime().nowMillis()

    /** The database whose invariants `@After` asserts; a test that opens another one assigns it here. */
    private lateinit var db: AppDatabase

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        db = databases.create()
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun indexes_keepSection9NamesWithoutRedundantNoteIndexes() {
        // Explicit indexes only: the automatic primary-key indexes have no SQL.
        val indexes = sql.stringsOf("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL")

        val expected = setOf(
            "idx_notes_date",
            "idx_notes_created",
            "idx_notes_updated",
            "idx_notes_checked_at",
            "idx_note_persons_person",
            "idx_note_projects_project",
        )
        assertEquals(expected, indexes.toSet()) // without idx_note_persons_note and idx_note_projects_note (DEC-30)
    }

    @Test
    fun userVersion_equalsDatabaseVersion() {
        assertEquals(AppDatabase.VERSION.toLong(), sql.longOf("PRAGMA user_version"))
    }

    @Test
    fun applicationId_isDoroOnCreate() {
        assertEquals(1_146_049_103L, sql.longOf("PRAGMA application_id")) // 0x444F524F, "DORO" (DEC-30)
    }

    @Test
    fun deletingHighestId_neverReusesIt() {
        listOf("notes" to ::insertNote, "persons" to ::insertPerson, "projects" to ::insertProject)
            .forEach { (table, insert) ->
                insert()
                val highest = insert()
                sql.execSQL("DELETE FROM $table WHERE id = ?", arrayOf(highest))

                assertEquals(table, highest + 1, insert()) // AUTOINCREMENT (DEC-30, INV-11)
            }
    }

    @Test
    fun foreignKeys_unknownNoteOrEntity_throwInsideTransaction() = runTest {
        val note = insertNote()
        val person = insertPerson()
        val project = insertProject()

        assertLinkRejected(PERSON_LINK, valid = note to person, rejected = note + 100 to person)
        assertLinkRejected(PERSON_LINK, valid = note to person, rejected = note to person + 100)
        assertLinkRejected(PROJECT_LINK, valid = note to project, rejected = note + 100 to project)
        assertLinkRejected(PROJECT_LINK, valid = note to project, rejected = note to project + 100)
    }

    @Test
    fun foreignKeys_enforcedAfterTwentyHolderCloseReopenCycles() = runTest {
        val holder = databases.holder()
        repeat(20) {
            holder.close()
            assertEquals(OpenResult.Opened, holder.open(TestDatabases.GENERATION, TestKey.Plain.bytes()))
        }
        db = holder.openDatabase()

        val note = insertNote()
        val person = insertPerson()
        assertLinkRejected(PERSON_LINK, valid = note to person, rejected = note + 100 to person)
    }

    @Test
    fun foreignKeys_noteDelete_leavesNoJunctionRows() {
        val note = insertNote()
        val kept = insertNote()
        val person = insertPerson()
        val project = insertProject()
        listOf(note, kept).forEach { id ->
            sql.execSQL(PERSON_LINK, arrayOf(id, person))
            sql.execSQL(PROJECT_LINK, arrayOf(id, project))
        }

        sql.execSQL("DELETE FROM notes WHERE id = ?", arrayOf(note))

        assertEquals(listOf(kept), sql.longsOf("SELECT note_id FROM note_persons"))
        assertEquals(listOf(kept), sql.longsOf("SELECT note_id FROM note_projects"))
    }

    @Test
    fun engineIsSqlcipher_plain() {
        assertCipherVersionReported()
    }

    @Test
    fun engineIsSqlcipher_keyed() {
        db = databases.create(TestKey.Raw(ByteArray(32) { it.toByte() }))

        assertCipherVersionReported()
    }

    private fun assertCipherVersionReported() {
        val version = sql.stringsOf("PRAGMA cipher_version").single()
        assertFalse("cipher_version empty", version.isNullOrEmpty()) // DEC-08: SQLCipher, not the framework engine
    }

    /**
     * One transaction on the IO dispatcher writes the [valid] link, then the [rejected] one: it throws and rolls back,
     * so not even the [valid] link remains (INV-7, CLAUDE.md rule 6).
     */
    private suspend fun assertLinkRejected(link: String, valid: Pair<Long, Long>, rejected: Pair<Long, Long>) {
        val error = withContext(Dispatchers.IO) {
            runCatching {
                db.withTransaction {
                    sql.execSQL(link, arrayOf(valid.first, valid.second))
                    sql.execSQL(link, arrayOf(rejected.first, rejected.second))
                }
            }.exceptionOrNull()
        }

        assertTrue("${error?.javaClass}", error is SQLiteConstraintException)
        assertEquals(0L, sql.longOf("SELECT COUNT(*) FROM note_persons"))
        assertEquals(0L, sql.longOf("SELECT COUNT(*) FROM note_projects"))
    }

    private fun insertNote(): Long = insert(
        "notes",
        "date" to null,
        "checked" to 0,
        "checked_at" to null,
        "content" to "",
        "content_fold" to "",
    )

    private fun insertPerson(): Long = insert("persons", "name" to "Ann")

    private fun insertProject(): Long = insert("projects", "name" to "Apollo")

    /**
     * Inserts a row with `created_at` and `updated_at` set to [now] and returns its id. Through `insert`, not
     * `last_insert_rowid()`, whose answer depends on which pooled connection runs the query.
     */
    private fun insert(table: String, vararg values: Pair<String, Any?>): Long {
        val row = contentValuesOf(*values, "created_at" to now, "updated_at" to now)
        return sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, row)
    }

    private companion object {
        const val PERSON_LINK = "INSERT INTO note_persons (note_id, person_id) VALUES (?, ?)"
        const val PROJECT_LINK = "INSERT INTO note_projects (note_id, project_id) VALUES (?, ?)"
    }
}
