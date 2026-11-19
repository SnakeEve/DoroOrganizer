package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TEST_DAY
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `NoteRepository`'s one-shot reads (data-layer.md § Editor loads, pickers and existence probes): `loadNote` maps
 * the note and sorts its links like row links (DEC-32), and `hasAnyData` probes for any row (DEC-73). Not writing
 * is `NoteWriteRulesTest.updatedAt_openingANote_writesNothing`; a gone note is `StaleIdTest`.
 */
@RunWith(AndroidJUnit4::class)
class NoteLoadTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private lateinit var rows: TestRows
    private lateinit var repository: NoteRepository

    @Before
    fun createDatabase() {
        val db: AppDatabase = databases.create()
        rows = TestRows(db.openHelper.writableDatabase)
        repository = noteRepository(db, time, applicationScope)
    }

    @Test
    fun loadNote_mapsTheNote_sortsLinksByCollatorThenId() = runTest(timeout = 10.seconds) {
        // Byte order would put "Ben" before "anna"; Collator does not. The two equal names fall back to the id.
        val ben = rows.person("Ben", now)
        val anna = rows.person("anna", now)
        val ben2 = rows.person("Ben", now)
        val zeta = rows.project("Zeta", now)
        val alpha = rows.project("alpha", now)
        val id = rows.note("text", createdAt = now, updatedAt = now + 1, checkedAt = now + 2, date = TEST_DAY)
        listOf(ben2, anna, ben).forEach { rows.linkPerson(id, it) }
        listOf(zeta, alpha).forEach { rows.linkProject(id, it) }

        val note = checkNotNull(repository.loadNote(id).ok())

        val expected = NoteDetail(id, "text", TEST_DAY, true, now + 2, now, now + 1, note.persons, note.projects)
        assertEquals(expected, note)
        assertEquals(listOf(anna, ben, ben2), note.persons.map { it.id })
        assertEquals(listOf(alpha, zeta), note.projects.map { it.id })
    }

    @Test
    fun hasAnyData_isFalseOnlyForAnEmptyDatabase() = runTest(timeout = 10.seconds) {
        assertFalse(repository.hasAnyData().ok())

        rows.project("X", now)

        assertTrue(repository.hasAnyData().ok())
    }
}
