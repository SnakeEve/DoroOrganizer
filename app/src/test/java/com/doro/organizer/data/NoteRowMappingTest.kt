package com.doro.organizer.data

import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteRow
import com.doro.organizer.testing.SENTINEL
import java.util.Locale
import java.util.Random
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** testing.md I-47: [toNoteRows] parses `persons_json`/`projects_json` exactly and sorts like the pickers (DEC-32). */
class NoteRowMappingTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun pinLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // the Collator follows the default locale
    }

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun hardNames_roundTripExactly() {
        val names = listOf(
            "say \"hi\"", "back\\slash", "a, b", "[x] ]y[", "line1\nline2", "smile 😀",
            "n".repeat(300), "Twin", "Twin",
        )
        val refs = names.mapIndexed { i, name -> EntityRef(i + 1L, name) }

        val row = map(raw(persons = json(refs), projects = json(refs.reversed())))

        assertEquals(refs.toSet(), row.persons.toSet())
        assertEquals(refs.size, row.persons.size)
        assertEquals(row.persons, row.projects)
    }

    @Test
    fun sqliteJsonEscapes_areDecoded() {
        // json_group_array(json_array(id, name)) as SQLite writes it: \" \\ \n escaped, other text verbatim.
        val row = map(raw(persons = """[[3,"a\"b"],[4,"c\\d"],[5,"e\nf"],[6,"€ 😀"]]"""))

        assertEquals(
            setOf(EntityRef(3, "a\"b"), EntityRef(4, "c\\d"), EntityRef(5, "e\nf"), EntityRef(6, "€ 😀")),
            row.persons.toSet(),
        )
    }

    @Test
    fun emptyArrays_giveNoLinks_andMarkTheRowOrphaned() {
        val row = map(raw())

        assertEquals(emptyList<EntityRef>(), row.persons)
        assertEquals(emptyList<EntityRef>(), row.projects)
        assertTrue(row.orphan)
        assertFalse(map(raw(projects = """[[1,"Apollo"]]""")).orphan)
    }

    @Test
    fun links_sortByCollatorOnName_thenId() {
        val refs = listOf(
            EntityRef(1, "cherry"), EntityRef(9, "Ann"), EntityRef(2, "Banana"), EntityRef(3, "Eve"),
            EntityRef(4, "Émile"), EntityRef(5, "apple"), EntityRef(7, "Ann"),
        )

        val row = map(raw(persons = json(refs), projects = json(refs.shuffled(Random(1)))))

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
            updatedAt = 20, personsJson = "[]", projectsJson = "[]",
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
        val rows = listOf(raw(id = 3), raw(id = 1), raw(id = 2)).toNoteRows()

        assertEquals(listOf(3L, 1L, 2L), rows.map { it.id }) // the SQL's canonical order (DEC-23)
    }

    @Test
    fun malformedJson_throwsConstantTextWithoutCause() {
        listOf("""[[1,"$SENTINEL""", """[["$SENTINEL",1]]""", """{"$SENTINEL":1}""").forEach { json ->
            val error = assertThrows(IllegalStateException::class.java) { map(raw(id = 77, persons = json)) }

            val message = error.message.orEmpty()
            assertFalse(message, message.contains(SENTINEL)) // DEC-70, security.md § 10
            assertTrue(message, message.contains("persons_json") && message.contains("77"))
            assertNull(error.cause)
        }
    }

    private fun map(raw: NoteRowRaw): NoteRow = listOf(raw).toNoteRows().single()

    private fun json(refs: List<EntityRef>): String =
        JSONArray(refs.map { JSONArray(listOf(it.id, it.name)) }).toString()

    private fun raw(
        id: Long = 1,
        date: Long? = 20_000,
        persons: String = "[]",
        projects: String = "[]",
    ) = NoteRowRaw(
        id = id, preview = "p", date = date, checked = false, checkedAt = null, createdAt = 1, updatedAt = 2,
        personsJson = persons, projectsJson = projects,
    )
}
