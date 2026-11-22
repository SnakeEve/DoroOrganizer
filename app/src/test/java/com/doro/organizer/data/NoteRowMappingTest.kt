package com.doro.organizer.data

import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteRow
import com.doro.organizer.testing.SENTINEL
import java.util.Locale
import java.util.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * testing.md I-47: [toNoteRows] splits `person_ids`/`project_ids` exactly, takes every name from the kind's id-to-name
 * map, waits (null) for a missing name and sorts like the pickers (DEC-32, DEC-106).
 */
class NoteRowMappingTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun pinLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // the Collator follows the default locale
    }

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    /** Q15 (A), DEC-106 (b): ids name their entities through the maps, and one unresolved id holds the whole list. */
    @Test
    fun linkIds_resolveThroughTheNameMaps_andARowWaitsForAMissingName() {
        val persons = mapOf(3L to "Cleo", 17L to "Ann", 5L to "Ann")
        val projects = mapOf(8L to "Zeta", 2L to "Apollo")
        val rows = listOf(raw(id = 1, persons = "17,3,5", projects = "8,2"), raw(id = 2, projects = "2"))

        val mapped = checkNotNull(rows.toNoteRows(persons, projects))

        assertEquals(listOf(EntityRef(5, "Ann"), EntityRef(17, "Ann"), EntityRef(3, "Cleo")), mapped[0].persons)
        assertEquals(listOf(EntityRef(2, "Apollo"), EntityRef(8, "Zeta")), mapped[0].projects)
        assertEquals(emptyList<EntityRef>(), mapped[1].persons)
        assertEquals(listOf(EntityRef(2, "Apollo")), mapped[1].projects)

        // A person id of the second row, or a project id of the first, that its map does not hold: no list at all.
        assertNull(listOf(rows[0], raw(id = 2, persons = "4")).toNoteRows(persons, projects))
        assertNull(rows.toNoteRows(persons, projects - 8L))
        // The kinds never share a map: a person id is not looked up among the projects.
        assertNull(listOf(raw(persons = "2")).toNoteRows(persons, projects))
    }

    @Test
    fun hardNames_comeOutExactlyAsTheMapHoldsThem() {
        val names = listOf(
            "say \"hi\"", "back\\slash", "a, b", "[x] ]y[", "line1\nline2", "smile 😀",
            "a\"b", "c\\d", "e\nf", "€ 😀", "n".repeat(300), "Twin", "Twin",
        )
        val refs = names.mapIndexed { i, name -> EntityRef(i + 1L, name) }
        val map = refs.associate { it.id to it.name }

        val row = map(raw(persons = ids(refs), projects = ids(refs.reversed())), map, map)

        assertEquals(refs.toSet(), row.persons.toSet())
        assertEquals(refs.size, row.persons.size)
        assertEquals(row.persons, row.projects)
    }

    @Test
    fun idLists_splitIntoExactlyTheirIds() {
        val big = Long.MAX_VALUE // 19 digits
        val cases = listOf(
            "7" to listOf(7L),
            "3,17" to listOf(3L, 17L),
            "17,3" to listOf(3L, 17L),
            "1000000000000,9" to listOf(9L, 1_000_000_000_000L),
            "$big,1" to listOf(1L, big),
            "1234567890123456789" to listOf(1_234_567_890_123_456_789L),
        )
        cases.forEach { (column, ids) ->
            val names = ids.associateWith { "e$it" }

            val row = map(raw(persons = column, projects = column), names, names)

            // The names sort as text, not by id, so compare the ids as a set plus the count.
            assertEquals(column, ids.toSet(), row.persons.map { it.id }.toSet())
            assertEquals(column, ids.size, row.persons.size)
            assertEquals(column, row.persons, row.projects)
            row.persons.forEach { assertEquals(column, "e${it.id}", it.name) }
        }
    }

    @Test
    fun nullIds_giveNoLinks_andMarkTheRowOrphaned() {
        val row = map(raw())

        assertEquals(emptyList<EntityRef>(), row.persons)
        assertEquals(emptyList<EntityRef>(), row.projects)
        assertTrue(row.orphan)
        assertFalse(map(raw(projects = "1"), projects = mapOf(1L to "Apollo")).orphan)
        assertFalse(map(raw(persons = "1"), persons = mapOf(1L to "Ann")).orphan)
    }

    @Test
    fun nullIds_neverLookUpAName() {
        // Empty maps: a row without links needs no name, so it maps.
        assertEquals(listOf(1L), listOf(raw()).toNoteRows(emptyMap(), emptyMap())?.map { it.id })
    }

    @Test
    fun links_sortByCollatorOnName_thenId() {
        val refs = listOf(
            EntityRef(1, "cherry"), EntityRef(9, "Ann"), EntityRef(2, "Banana"), EntityRef(3, "Eve"),
            EntityRef(4, "Émile"), EntityRef(5, "apple"), EntityRef(7, "Ann"),
        )
        val names = refs.associate { it.id to it.name }

        val row = map(raw(persons = ids(refs), projects = ids(refs.shuffled(Random(1)))), names, names)

        // String order would put "Banana" before "apple" and "Émile" after "cherry".
        val expected = listOf(
            EntityRef(7, "Ann"), EntityRef(9, "Ann"), EntityRef(5, "apple"), EntityRef(2, "Banana"),
            EntityRef(1, "cherry"), EntityRef(4, "Émile"), EntityRef(3, "Eve"),
        )
        assertEquals(expected, row.persons)
        assertEquals(expected, row.projects)
    }

    @Test
    fun rowColumns_areCopied() {
        val raw = NoteRowRaw(
            id = 42, preview = "Preview", date = 20_774, checked = true, checkedAt = 30, createdAt = 10,
            updatedAt = 20, personIds = null, projectIds = null,
        )

        val expected = NoteRow(
            id = 42, preview = "Preview", date = 20_774, checked = true, checkedAt = 30, createdAt = 10,
            updatedAt = 20, persons = emptyList(), projects = emptyList(),
        )
        assertEquals(expected, map(raw))
        assertNull(map(raw(date = null)).date)
    }

    @Test
    fun emission_keepsRowOrder() {
        val rows = checkNotNull(listOf(raw(id = 3), raw(id = 1), raw(id = 2)).toNoteRows(emptyMap(), emptyMap()))

        assertEquals(listOf(3L, 1L, 2L), rows.map { it.id }) // the SQL's canonical order (DEC-23)
    }

    @Test
    fun malformedIds_throwConstantTextWithoutCause() {
        val malformed = listOf(
            SENTINEL, "1,$SENTINEL", "$SENTINEL,1", "", "1,", ",1", "1,,2", " 1", "1 ", "1;2", "1.5", "0x1F",
            "[1]", "99999999999999999999", "1,2$SENTINEL",
        )
        val names = mapOf(1L to "Ann", 2L to "Bob")
        malformed.forEach { ids ->
            listOf("person_ids" to raw(id = 77, persons = ids), "project_ids" to raw(id = 77, projects = ids))
                .forEach { (column, raw) ->
                    val error = assertThrows("$column '$ids'", IllegalStateException::class.java) {
                        listOf(raw).toNoteRows(names, names)
                    }

                    val message = error.message.orEmpty()
                    assertFalse(message, message.contains(SENTINEL)) // DEC-70, security.md § 10
                    assertTrue(message, message.contains(column) && message.contains("77"))
                    assertEquals(message, "$column of note 77 is malformed", message)
                    assertNull(error.cause)
                }
        }
    }

    private fun map(
        raw: NoteRowRaw,
        persons: Map<Long, String> = emptyMap(),
        projects: Map<Long, String> = emptyMap(),
    ): NoteRow = checkNotNull(listOf(raw).toNoteRows(persons, projects)).single()

    /** The ids of [refs] as `group_concat` writes them. */
    private fun ids(refs: List<EntityRef>): String = refs.joinToString(",") { it.id.toString() }

    private fun raw(
        id: Long = 1,
        date: Long? = 20_000,
        persons: String? = null,
        projects: String? = null,
    ) = NoteRowRaw(
        id = id, preview = "p", date = date, checked = false, checkedAt = null, createdAt = 1, updatedAt = 2,
        personIds = persons, projectIds = projects,
    )
}
