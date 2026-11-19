package com.doro.organizer.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.NotePersonCrossRef
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.BatchOracle
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.NoteOracle
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TEST_DAY
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.seed
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Batch actions of data-layer.md § Batch operations (DEC-36), repository part: one transaction per action across all
 * chunks of `SQL_CHUNK` ids (testing.md I-19), `INSERT OR IGNORE` idempotency (I-20), and Select all plus chunked
 * actions against a Kotlin oracle (I-21). Rollbacks are injected with a real trigger (testing.md § 7), which raises
 * a constraint failure: a bug, so it reaches the caller and the application scope's handler alike.
 */
@RunWith(AndroidJUnit4::class)
class BatchTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows
    private lateinit var notes: NoteRepository
    private lateinit var entities: EntityRepository
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        db = databases.create()
        rows = TestRows(sql)
        notes = noteRepository(db, time, applicationScope)
        entities = entityRepository(db, time, applicationScope)
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun rollbackAssociate_inTheThirdChunk_leavesEverythingAsBefore() = runTest(timeout = 60.seconds) {
        val person = rows.person("P", time.nowMillis())
        val project = rows.project("X", time.nowMillis())
        val ids = insertNotes(ROLLBACK_NOTES)

        assertRollsBack(abortWhen("BEFORE INSERT ON note_projects", "NEW.note_id = ${ids.last()}")) {
            notes.associate(ids, setOf(person), setOf(project), listOf(NewEntity("k", "Created")), emptyList())
        }
    }

    @Test
    fun rollbackSetDate_inTheThirdChunk_leavesEverythingAsBefore() = runTest(timeout = 60.seconds) {
        val ids = insertNotes(ROLLBACK_NOTES)

        assertRollsBack(abortWhen("BEFORE UPDATE OF date ON notes", "NEW.id = ${ids.last()}")) {
            notes.setDate(ids, TEST_DAY)
        }
    }

    @Test
    fun rollbackDelete_inTheThirdChunk_leavesEverythingAsBefore() = runTest(timeout = 60.seconds) {
        val person = rows.person("P", time.nowMillis())
        val ids = insertNotes(ROLLBACK_NOTES, linkTo = person)

        assertRollsBack(abortWhen("BEFORE DELETE ON notes", "OLD.id = ${ids.last()}")) {
            notes.deleteNotes(ids)
        }
    }

    @Test
    fun rollbackEntityDelete_afterItsLinks_leavesEverythingAsBefore() = runTest(timeout = 60.seconds) {
        val person = rows.person("P", time.nowMillis())
        insertNotes(ROLLBACK_NOTES, linkTo = person)

        assertRollsBack(abortWhen("BEFORE DELETE ON persons", "OLD.id = $person")) {
            entities.deletePerson(person)
        }
    }

    @Test
    fun associateIdempotent_secondRunChangesAndBumpsNothing() = runTest(timeout = 10.seconds) {
        val t0 = time.nowMillis()
        val p = rows.person("P", t0)
        val q = rows.person("Q", t0)
        val a = rows.note("a", createdAt = t0)
        val b = rows.note("b", createdAt = t0)
        rows.linkPerson(a, p)
        val t1 = advance()

        val first = notes.associate(listOf(a, b), setOf(p, q), emptySet(), emptyList(), emptyList()).ok()

        assertEquals("a gains one of two chosen links and is bumped", BatchResult(2, 2), first)
        assertEquals(listOf(t1, t1), updatedAts(a, b))
        val links = canonicalDump(sql).getValue("note_persons")
        advance()

        val second = notes.associate(listOf(a, b), setOf(p, q), emptySet(), emptyList(), emptyList()).ok()

        assertEquals(BatchResult(2, 0), second)
        assertEquals(links, canonicalDump(sql).getValue("note_persons"))
        assertEquals("nobody bumped the second time", listOf(t1, t1), updatedAts(a, b))
    }

    @Test
    fun associateIdempotent_ignoredInsertReturnsMinusOneForExistingPairsOnly() = runTest(timeout = 10.seconds) {
        val t0 = time.nowMillis()
        val p = rows.person("P", t0)
        val q = rows.person("Q", t0)
        val a = rows.note("a", createdAt = t0)
        rows.linkPerson(a, p)
        val pairs = listOf(NotePersonCrossRef(a, p), NotePersonCrossRef(a, q), NotePersonCrossRef(a, q))

        val first = db.withTransaction { db.noteDao().insertPersonLinks(pairs) }

        assertEquals("a pair that existed before the call is ignored", -1L, first[0])
        assertTrue("a new pair gets its rowid: $first", first[1] > 0)
        assertEquals("the pair inserted one step earlier already exists", -1L, first[2])

        val second = db.withTransaction { db.noteDao().insertPersonLinks(pairs.take(2)) }

        assertEquals("the second call inserts nothing", listOf(-1L, -1L), second)
    }

    @Test
    fun chunks_associateSetDateDelete_matchTheOracle() = runTest(timeout = 120.seconds) {
        CHUNK_SIZES.forEach { count -> chunkCase(count) }
    }

    @Test
    fun selectAll_onTheSeed_thenDelete_removesEveryNoteAndLink() = runTest(timeout = 120.seconds) {
        val model = db.seed()

        val ids = notes.selectIds(ALL_NOTES).ok()

        assertEquals(model.notes.map { it.id }.toSet(), ids.toSet())
        assertEquals(BatchResult(selected = 10_000, changed = 10_000), notes.deleteNotes(ids).ok())
        listOf("notes", "note_persons", "note_projects").forEach { table ->
            assertEquals(table, 0L, sql.longOf("SELECT COUNT(*) FROM $table"))
        }
        assertEquals("entities stay", model.persons.size.toLong(), sql.longOf("SELECT COUNT(*) FROM persons"))
    }

    @Test
    fun selectAll_coversTheFullFilteredQuery_checkedIncluded() = runTest(timeout = 60.seconds) {
        val model = db.seed()
        val heavyPerson = SeedGenerator.HEAVY_PERSON
        val heavyProject = SeedGenerator.HEAVY_PROJECT
        val year = DateFilter.Range(SeedGenerator.BASE_DAY - 180, SeedGenerator.BASE_DAY + 180)
        val heavyPersonOnly = NoteFilter(Perspective.Individual, primaryId = heavyPerson)
        val filters = listOf(
            NoteFilter(Perspective.All),
            NoteFilter(Perspective.All, date = DateFilter.Undated),
            heavyPersonOnly,
            NoteFilter(Perspective.Individual, primaryId = heavyPerson, search = "a"),
            NoteFilter(Perspective.Individual, primaryId = heavyPerson, projectId = heavyProject, search = "e"),
            NoteFilter(Perspective.Project, primaryId = heavyProject, personId = heavyPerson),
            NoteFilter(Perspective.Date, primaryDays = year),
            NoteFilter(Perspective.Orphan),
        )
        val checked = model.notes.filter { it.checked }.map { it.id }.toSet()
        val heavySelection = NoteOracle.listIds(model, heavyPersonOnly).size
        assertTrue("a filtered selection spans chunks: $heavySelection", heavySelection > SQL_CHUNK)

        filters.forEach { filter ->
            val expected = NoteOracle.listIds(model, filter).toSet()
            val selected = notes.selectIds(checkNotNull(filter.toQuery())).ok()

            assertEquals("$filter", expected, selected.toSet())
            assertEquals("$filter: no id twice", selected.size, selected.toSet().size)
            assertTrue("$filter: checked notes are selected", selected.any { it in checked })
        }
    }

    /**
     * I-21: [count] selected notes plus [UNSELECTED] others, every third already on [TEST_DAY], every second linked to
     * P. Associate P, set [TEST_DAY], then delete, each at its own time, and compare every row with [BatchOracle].
     */
    private suspend fun chunkCase(count: Int) {
        sql.execSQL("DELETE FROM note_persons")
        sql.execSQL("DELETE FROM notes")
        val t0 = time.nowMillis()
        val person = rows.person("P$count", t0)
        val selected = insertNotes(count + UNSELECTED, linkTo = person, date = TEST_DAY).take(count)
        val oracle = BatchOracle(sql, person)
        assertEquals("$count: setup", oracle.state(), oracle.read())

        val t1 = advance()
        val associated = notes.associate(selected, setOf(person), emptySet(), emptyList(), emptyList()).ok()
        assertEquals("$count: associate", BatchResult(count, oracle.associate(selected, t1)), associated)
        assertEquals("$count: after associate", oracle.state(), oracle.read())

        val t2 = advance()
        val dated = notes.setDate(selected, TEST_DAY).ok()
        assertEquals("$count: set date", BatchResult(count, oracle.setDate(selected, TEST_DAY, t2)), dated)
        assertEquals("$count: after set date", oracle.state(), oracle.read())

        advance()
        val deleted = notes.deleteNotes(selected).ok()
        assertEquals("$count: delete", BatchResult(count, oracle.delete(selected)), deleted)
        assertEquals("$count: after delete", oracle.state(), oracle.read())
        assertDbInvariants(sql)
    }

    /**
     * Inserts [count] notes at the current time in one transaction and returns their ids in insertion order. Every
     * second note is linked to [linkTo] when given; every third gets [date].
     */
    private fun insertNotes(count: Int, linkTo: Long? = null, date: Long? = null): List<Long> {
        val now = time.nowMillis()
        sql.beginTransaction()
        try {
            val ids = List(count) { i ->
                rows.note("n$i", createdAt = now, date = date.takeIf { i % 3 == 0 }).also { id ->
                    if (linkTo != null && i % 2 == 0) rows.linkPerson(id, linkTo)
                }
            }
            sql.setTransactionSuccessful()
            return ids
        } finally {
            sql.endTransaction()
        }
    }

    /** Installs [trigger], runs [action], and asserts that it failed with the injected abort and changed nothing. */
    private suspend fun assertRollsBack(trigger: String, action: suspend () -> Any) {
        sql.execSQL(trigger)
        advance()
        val before = canonicalDump(sql)

        val thrown = runCatching { action() }.exceptionOrNull()

        assertTrue("the caller sees the abort: $thrown", thrown is SQLiteConstraintException)
        val handled = applicationScope.takeFailures()
        assertTrue("the scope's handler sees it once: $handled", handled.single() is SQLiteConstraintException)
        assertEquals(before, canonicalDump(sql))
    }

    private fun abortWhen(event: String, condition: String) =
        "CREATE TRIGGER t_fail $event WHEN $condition BEGIN SELECT RAISE(ABORT, 'injected'); END"

    private fun advance(): Long {
        time.advanceBy(Duration.ofMinutes(1))
        return time.nowMillis()
    }

    private fun updatedAts(vararg ids: Long): List<Long> =
        ids.map { sql.longOf("SELECT updated_at FROM notes WHERE id = $it") }

    private companion object {
        const val ROLLBACK_NOTES = 1_850 // three chunks of SQL_CHUNK = 900
        const val UNSELECTED = 7
        val CHUNK_SIZES = listOf(899, 900, 901, 1_850)
    }
}
