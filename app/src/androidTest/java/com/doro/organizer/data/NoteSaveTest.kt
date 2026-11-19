package com.doro.organizer.data

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.NewEntity
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.noteRepository
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** testing.md I-19, save part: a note save is one transaction, its new entities and links included (DEC-33). */
@RunWith(AndroidJUnit4::class)
class NoteSaveTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private lateinit var db: AppDatabase
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        db = databases.create()
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun rollback() = runTest(timeout = 10.seconds) {
        val repository = noteRepository(db, FakeAppTime(), applicationScope)
        // Aborts the second link insert of a note, after the note, both persons and the first link are written.
        sql.execSQL(
            "CREATE TRIGGER t_fail BEFORE INSERT ON note_persons " +
                "WHEN EXISTS (SELECT 1 FROM note_persons WHERE note_id = NEW.note_id) " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )
        val before = canonicalDump(sql)
        val newPersons = listOf(NewEntity("a", "Ann"), NewEntity("b", "Bob"))

        val thrown = runCatching { repository.save(draft(content = "note", newPersons = newPersons)) }.exceptionOrNull()

        assertTrue("the caller sees the abort: $thrown", thrown is SQLiteConstraintException)
        assertTrue(applicationScope.takeFailures().single() is SQLiteConstraintException)
        assertEquals("no note, person or link", before, canonicalDump(sql))
    }
}
