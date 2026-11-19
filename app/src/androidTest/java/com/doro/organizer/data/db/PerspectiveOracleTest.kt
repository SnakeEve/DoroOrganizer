package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.NoteOracle
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.SeedModel
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.listedIds
import com.doro.organizer.testing.markedDays
import com.doro.organizer.testing.seed
import com.doro.organizer.testing.selectedIds
import java.time.LocalDate
import java.util.Random
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
 * testing.md I-14: for 200 seeded random [NoteFilter]s across the five perspectives on the 10,000-note seed, the
 * perspective query returns exactly the ids, in exactly the order, of the independent [NoteOracle]. Select all
 * (DEC-36) returns the same set, and in the Date perspective the month's marks (DEC-53) match the oracle's.
 */
@RunWith(AndroidJUnit4::class)
class PerspectiveOracleTest {
    @get:Rule
    val databases = TestDatabases()

    private lateinit var db: AppDatabase
    private lateinit var model: SeedModel
    private lateinit var linkedPersons: List<Long>
    private lateinit var linkedProjects: List<Long>

    @Before
    fun seedDatabase() {
        db = databases.create()
        model = db.seed()
        // Drawn per link, so filters favour entities that have notes, the heavy ones most.
        linkedPersons = model.notes.flatMap { it.personIds }
        linkedProjects = model.notes.flatMap { it.projectIds }
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun randomFilters_matchTheKotlinReference() = runTest(timeout = 120.seconds) {
        val random = Random(SEED)
        var nonEmpty = 0
        repeat(FILTERS) { i ->
            val filter = randomFilter(random, Perspective.entries[i % Perspective.entries.size])
            val query = checkNotNull(filter.toQuery())
            val expected = NoteOracle.listIds(model, filter)

            assertEquals("#$i $filter", expected, db.noteDao().listedIds(query))
            assertEquals("#$i select all", expected.sorted(), db.noteDao().selectedIds(query).sorted())
            if (filter.perspective == Perspective.Date) assertMarks(i, filter, query)
            if (expected.isNotEmpty()) nonEmpty++
        }
        assertTrue("only $nonEmpty of $FILTERS lists had rows", nonEmpty >= FILTERS / 5) // never vacuous
    }

    private suspend fun assertMarks(i: Int, filter: NoteFilter, query: NoteQuery) {
        val month = LocalDate.ofEpochDay(query.fromDay!!).withDayOfMonth(1)
        val first = month.toEpochDay()
        val last = month.plusMonths(1).toEpochDay() - 1

        val marked = db.noteDao().markedDays(first, last, query)

        assertEquals("#$i marks", NoteOracle.markedDays(model, filter, first, last), marked)
    }


    private fun randomFilter(random: Random, perspective: Perspective) = NoteFilter(
        perspective = perspective,
        primaryId = when (perspective) {
            Perspective.Individual -> linkedPersons.pick(random)
            Perspective.Project -> linkedProjects.pick(random)
            else -> null
        },
        primaryDays = if (perspective == Perspective.Date) randomDays(random) else null,
        personId = if (random.nextInt(3) == 0) linkedPersons.pick(random) else null,
        projectId = if (random.nextInt(3) == 0) linkedProjects.pick(random) else null,
        date = when (random.nextInt(6)) {
            0, 1 -> DateFilter.Any
            2 -> DateFilter.Undated
            3 -> DateFilter.Range(null, null)
            4 -> DateFilter.Range(maybeDay(random), maybeDay(random))
            else -> DateFilter.Range(day(random), day(random)) // may be reversed
        },
        search = randomSearch(random),
    )

    /** A single day, or a range of up to two months, possibly reversed. */
    private fun randomDays(random: Random): DateFilter.Range {
        val from = day(random)
        val to = if (random.nextBoolean()) from else from + random.nextInt(70) - 10
        return DateFilter.Range(from, to)
    }

    private fun day(random: Random): Long = SeedGenerator.BASE_DAY + random.nextInt(731) - 365

    private fun maybeDay(random: Random): Long? = day(random).takeIf { random.nextBoolean() }

    private fun randomSearch(random: Random): String = when (random.nextInt(5)) {
        0, 1 -> ""
        2 -> SeedGenerator.WORDS.pick(random).let { if (random.nextBoolean()) it.uppercase() else it }
        3 -> contentSlice(random)
        else -> listOf("%", "_", "\\", "50%", "a_b", "  C:\\X ", " ").pick(random)
    }

    /** 2 to 8 characters of a random note's content, never splitting a surrogate pair. */
    private fun contentSlice(random: Random): String {
        val content = model.notes.filter { !TextWhitespace.isBlank(it.content) }.pick(random).content
        var start = random.nextInt(content.length - 1)
        if (Character.isLowSurrogate(content[start])) start--
        var end = minOf(content.length, start + 2 + random.nextInt(7))
        if (Character.isHighSurrogate(content[end - 1])) end++
        return content.substring(start, end)
    }

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    private companion object {
        const val SEED = 7L
        const val FILTERS = 200
    }
}
