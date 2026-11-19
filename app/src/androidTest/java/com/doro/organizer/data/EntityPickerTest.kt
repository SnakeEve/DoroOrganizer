package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.NewEntity
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-23, repository part: `EntityRepository.observeOptions` lists every entity, zero-note ones included
 * (DEC-33), with its note count and creation time, ordered co-linked first (DEC-28), then by `Collator` on the name,
 * then by id (DEC-32); names created on confirm are trimmed, never deduplicated, and a blank one writes nothing.
 * The JVM `EntityOptionMappingTest` pins the order itself; the Compose half of I-23 joins this class with the picker.
 */
@RunWith(AndroidJUnit4::class)
class EntityPickerTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private lateinit var defaultLocale: Locale
    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows
    private lateinit var entities: EntityRepository
    private lateinit var notes: NoteRepository
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // the Collator follows the default locale
        holder = databases.holder()
        db = holder.openDatabase()
        rows = TestRows(sql)
        entities = entityRepository(holder, time, applicationScope)
        notes = noteRepository(holder, time, applicationScope)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        assertDbInvariants(sql)
    }

    @Test
    fun persons_everyEntityWithCounts_inCollatorThenIdOrder() = runTest(timeout = 10.seconds) {
        val zed = rows.person("Zed", now)
        val twinB = rows.person("twin", now + 1)
        val anna = rows.person("anna", now + 2)
        val twinA = rows.person("twin", now + 3)
        val emile = rows.person("Émile", now + 4)
        val note = rows.note("n", createdAt = now)
        rows.linkPerson(note, anna)
        rows.linkPerson(note, twinA)
        rows.linkPerson(rows.note("m", createdAt = now), anna)

        val options = entities.observeOptions(EntityKind.Person, coLinkedTo = null).first()

        assertEquals(
            listOf(
                EntityOption(anna, "anna", now + 2, noteCount = 2, coLinked = false),
                EntityOption(emile, "Émile", now + 4, noteCount = 0, coLinked = false),
                EntityOption(twinB, "twin", now + 1, noteCount = 0, coLinked = false),
                EntityOption(twinA, "twin", now + 3, noteCount = 1, coLinked = false),
                EntityOption(zed, "Zed", now, noteCount = 0, coLinked = false),
            ),
            options,
        )
    }

    @Test
    fun personFilterOfAProject_listsCoLinkedPersonsFirst() = runTest(timeout = 10.seconds) {
        val anna = rows.person("Anna", now)
        val zed = rows.person("Zed", now)
        val project = rows.project("X", now)
        val note = rows.note("n", createdAt = now)
        rows.linkProject(note, project)
        rows.linkPerson(note, zed)

        assertEquals(listOf(zed, anna), ids(entities.observeOptions(EntityKind.Person, coLinkedTo = project).first()))
        assertEquals(listOf(anna, zed), ids(entities.observeOptions(EntityKind.Person, coLinkedTo = null).first()))
    }

    @Test
    fun projectFilterOfAPerson_listsCoLinkedProjectsFirst() = runTest(timeout = 10.seconds) {
        val apollo = rows.project("Apollo", now)
        val zeus = rows.project("Zeus", now)
        val person = rows.person("P", now)
        val note = rows.note("n", createdAt = now)
        rows.linkPerson(note, person)
        rows.linkProject(note, zeus)

        val options = entities.observeOptions(EntityKind.Project, coLinkedTo = person).first()

        assertEquals(listOf(zeus, apollo), ids(options))
        assertEquals(listOf(true, false), options.map { it.coLinked })
    }

    @Test
    fun createdOnConfirm_trimmedAndNeverMerged_thenListed() = runTest(timeout = 10.seconds) {
        val note = rows.note("n", createdAt = now)

        entities.observeOptions(EntityKind.Person, coLinkedTo = null).test {
            assertEquals(emptyList<EntityOption>(), awaitItem())

            val created = listOf(NewEntity("a", "  Twin\t"), NewEntity("b", "Twin"))
            notes.associate(listOf(note), emptySet(), emptySet(), created, emptyList()).ok()

            val options = awaitItem()
            assertEquals(listOf("Twin", "Twin"), options.map { it.name })
            assertEquals(listOf(1, 1), options.map { it.noteCount })
        }
    }

    @Test
    fun blankNameOnConfirm_isInvalidInput_andWritesNothing() = runTest(timeout = 10.seconds) {
        val note = rows.note("n", createdAt = now)
        val before = canonicalDump(sql)

        val result = notes.associate(
            listOf(note), emptySet(), emptySet(), listOf(NewEntity("a", "Ann")), listOf(NewEntity("b", " \n ")),
        )

        assertEquals(AppResult.Err(AppError.InvalidInput(InputRule.BlankName)), result)
        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun deletedEntity_leavesThePicker() = runTest(timeout = 10.seconds) {
        val keep = rows.project("Keep", now)
        val gone = rows.project("Gone", now)

        entities.observeOptions(EntityKind.Project, coLinkedTo = null).test {
            assertEquals(listOf(gone, keep), ids(awaitItem()))

            entities.deleteProject(gone).ok()

            assertEquals(listOf(keep), ids(awaitItem()))
        }
    }

    private fun ids(options: List<EntityOption>): List<Long> = options.map { it.id }
}
