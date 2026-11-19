package com.doro.organizer.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EpochDays
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.seed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-43: `EXPLAIN QUERY PLAN` of every read statement constant on the 10,000-note seed, against the table
 * of data-layer.md § Size and performance. No plan ever scans a junction table; the expected indexes are named.
 * The write statements (impact counts, deletes) join this test with the tasks that add them.
 */
@RunWith(AndroidJUnit4::class)
class QueryPlanTest {
    @get:Rule
    val databases = TestDatabases()

    private lateinit var db: AppDatabase
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun seedDatabase() {
        db = databases.create()
        db.seed()
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun noteLists_scanNotesOnce_andSearchJunctionKeys() {
        LIST_BINDINGS.forEach { (label, args) ->
            val rows = plan(ROWS_SQL, args)
            assertTrue("$label rows: $rows", rows.any { SCAN_NOTES.matches(it) })
            assertTrue("$label rows: $rows", rows.any { it.startsWith("USE TEMP B-TREE FOR ORDER BY") })
            assertUses(rows, "np", AUTOINDEX_PERSONS, "$label rows")
            assertUses(rows, "nj", AUTOINDEX_PROJECTS, "$label rows")
            assertPerspectiveSubqueriesSearchKeys(rows, "$label rows")

            val ids = plan(IDS_SQL, args)
            assertTrue("$label ids: $ids", ids.any { SCAN_NOTES.matches(it) })
            assertFalse("$label ids: $ids", ids.any { it.contains("TEMP B-TREE") })
            assertPerspectiveSubqueriesSearchKeys(ids, "$label ids")
        }
    }

    @Test
    fun markedDays_searchTheDateIndex() {
        val month = mapOf("fromDay" to SeedGenerator.BASE_DAY - 16, "toDay" to SeedGenerator.BASE_DAY + 13)
        val filters = listOf(
            mapOf("personId" to null, "projectId" to null, "searchPattern" to null),
            mapOf("personId" to 1L, "projectId" to 1L, "searchPattern" to "50\\%"),
        )

        filters.forEach { filter ->
            val plan = plan(MARKED_DAYS_SQL, month + filter)
            assertTrue("$plan", plan.any { DATE_INDEX.containsMatchIn(it) })
        }
    }

    @Test
    fun pickers_countThroughTheEntityIndexes() {
        listOf(null, 1L).forEach { coLinkedTo ->
            val persons = plan(PERSON_OPTIONS_SQL, mapOf("coLinkedTo" to coLinkedTo))
            assertUses(persons, "c", "idx_note_persons_person", "persons")
            assertTrue("persons: $persons", persons.any { ENTITY_INDEX.containsMatchIn(it) && it.matches(COLINKED) })

            val projects = plan(PROJECT_OPTIONS_SQL, mapOf("coLinkedTo" to coLinkedTo))
            assertUses(projects, "c", "idx_note_projects_project", "projects")
            assertTrue("projects: $projects", projects.any { ENTITY_INDEX.containsMatchIn(it) && it.matches(COLINKED) })
        }
    }

    @Test
    fun editorLoadsAndProbes_searchByKey() {
        assertUses(plan(LINKED_PERSONS_SQL, mapOf("noteId" to 1L)), "x", AUTOINDEX_PERSONS, "linked persons")
        assertUses(plan(LINKED_PROJECTS_SQL, mapOf("noteId" to 1L)), "y", AUTOINDEX_PROJECTS, "linked projects")
        listOf(
            plan(NOTE_DETAIL_SQL, mapOf("id" to 1L)) to "notes",
            plan(PERSON_REF_SQL, mapOf("id" to 1L)) to "persons",
            plan(PROJECT_REF_SQL, mapOf("id" to 1L)) to "projects",
        ).forEach { (plan, table) ->
            assertEquals(listOf("SEARCH $table USING INTEGER PRIMARY KEY (rowid=?)"), plan)
        }

        // Each EXISTS stops at the first row it reads, so a scan of each table is the expected, constant-cost plan.
        val anyData = plan(HAS_ANY_DATA_SQL, emptyMap())
        listOf("notes", "persons", "projects").forEach { table ->
            assertTrue("has any data: $anyData", anyData.any { Regex("""^SCAN $table\b""").containsMatchIn(it) })
        }
    }

    /** The EXISTS subqueries of `PERSPECTIVE_WHERE` probe the junction primary keys by `note_id`. */
    private fun assertPerspectiveSubqueriesSearchKeys(plan: List<String>, label: String) {
        assertUses(plan, "x", AUTOINDEX_PERSONS, label)
        assertUses(plan, "y", AUTOINDEX_PROJECTS, label)
        assertUses(plan, "a", AUTOINDEX_PERSONS, label)
        assertUses(plan, "b", AUTOINDEX_PROJECTS, label)
    }

    /**
     * The plan rows of [statement] with [args] bound by name. SQLite numbers named parameters by their first
     * appearance and shares one number among repeats, so binding the distinct names in that order binds each
     * occurrence. Every plan is also checked for scans of a junction table under any alias the statement gives it.
     */
    private fun plan(statement: String, args: Map<String, Any?>): List<String> {
        val names = PARAMETER.findAll(statement).map { it.groupValues[1] }.distinct().toList()
        assertEquals("parameters of the statement", names.toSet(), args.keys)
        val bound = names.map { name ->
            when (val value = args[name]) {
                is Boolean -> if (value) 1L else 0L
                else -> value
            }
        }
        val plan = sql.query("EXPLAIN QUERY PLAN $statement", bound.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }
        val junctionScan = junctionScan(statement)
        assertFalse("junction table scanned: $plan", plan.any { junctionScan.containsMatchIn(it) })
        return plan
    }

    /**
     * Matches a plan row that scans `note_persons` or `note_projects`, by name or by any alias [statement] gives
     * them, so a new alias can never slip past the guard.
     */
    private fun junctionScan(statement: String): Regex {
        val aliases = JUNCTION_ALIAS.findAll(statement).map { it.groupValues[1] }.toSet()
        assertEquals("aliases $aliases", JUNCTION_TABLE.containsMatchIn(statement), aliases.isNotEmpty())
        val names = (aliases + JUNCTION_TABLES).joinToString("|")
        return Regex("""^SCAN (?:TABLE )?(?:$names)\b""")
    }

    /** Every plan row that reads [alias] searches [index]. */
    private fun assertUses(plan: List<String>, alias: String, index: String, label: String) {
        val reads = plan.filter { Regex("""^(SCAN|SEARCH) $alias\b""").containsMatchIn(it) }
        assertTrue("$label: no row reads $alias in $plan", reads.isNotEmpty())
        reads.forEach { assertTrue("$label: $it", it.startsWith("SEARCH $alias USING") && it.contains(index)) }
    }

    private companion object {
        val PARAMETER = Regex(""":(\w+)""")

        val JUNCTION_TABLES = listOf("note_persons", "note_projects")
        val JUNCTION_TABLE = Regex("""\bnote_(?:persons|projects)\b""")
        val JUNCTION_ALIAS = Regex("""\bnote_(?:persons|projects)\s+(?:AS\s+)?(\w+)""")
        val SCAN_NOTES = Regex("""^SCAN n\b.*""")
        val DATE_INDEX = Regex("""^SEARCH n USING (?:COVERING )?INDEX idx_notes_date\b""")
        val ENTITY_INDEX = Regex("""idx_note_persons_person|idx_note_projects_project""")
        val COLINKED = Regex("""^SEARCH [xy] USING .*""")
        const val AUTOINDEX_PERSONS = "sqlite_autoindex_note_persons_1"
        const val AUTOINDEX_PROJECTS = "sqlite_autoindex_note_projects_1"

        private val ALL_NULL = mapOf(
            "personId" to null, "projectId" to null, "orphanOnly" to false, "undatedOnly" to false,
            "fromDay" to null, "toDay" to null, "searchPattern" to null,
        )

        /** The bindings of testing.md I-43: all null, person, project, orphanOnly, range and search. */
        val LIST_BINDINGS: List<Pair<String, Map<String, Any?>>> = listOf(
            "all null" to ALL_NULL,
            "person" to ALL_NULL + ("personId" to 1L),
            "project" to ALL_NULL + ("projectId" to 1L),
            "orphanOnly" to ALL_NULL + ("orphanOnly" to true),
            "range" to ALL_NULL + mapOf("fromDay" to EpochDays.MIN, "toDay" to EpochDays.MAX),
            "search" to ALL_NULL + ("searchPattern" to "a\\_b"),
        )
    }
}
