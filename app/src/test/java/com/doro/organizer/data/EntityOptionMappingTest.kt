package com.doro.organizer.data

import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.model.EntityOption
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * testing.md I-23, repository part: [toEntityOptions] orders picker options co-linked first (DEC-28), then by
 * `Collator` on the name, then by id (DEC-32), and keeps every field and every duplicate.
 */
class EntityOptionMappingTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun pinLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // the Collator follows the default locale
    }

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun coLinkedEntitiesComeFirst() {
        val options = listOf(row(1, "Anna"), row(2, "Zed", coLinked = true)).toEntityOptions()

        assertEquals("a co-linked Zed precedes a non-co-linked Anna", listOf(2L, 1L), options.map { it.id })
    }

    @Test
    fun namesSortByCollator_notByCodePoint() {
        val names = listOf("zoe", "Émile", "anna", "Bob", "émile")

        val sorted = names.mapIndexed { i, name -> row(i + 1L, name) }.toEntityOptions().map { it.name }

        // Code-point order would be Bob, anna, zoe, Émile, émile.
        assertEquals(listOf("anna", "Bob", "émile", "Émile", "zoe"), sorted)
    }

    @Test
    fun equalNamesSortById_andStayTwoOptions() {
        val options = listOf(row(9, "Twin"), row(3, "Twin"), row(5, "Twin")).toEntityOptions()

        assertEquals("duplicates are never merged (§1)", listOf(3L, 5L, 9L), options.map { it.id })
    }

    @Test
    fun groupsSortIndependently() {
        val rows = listOf(
            row(1, "b"), row(2, "a", coLinked = true), row(3, "a"), row(4, "c", coLinked = true), row(5, "b"),
        )

        assertEquals(listOf(2L, 4L, 3L, 1L, 5L), rows.toEntityOptions().map { it.id })
    }

    @Test
    fun everyFieldIsCarriedOver() {
        val option = listOf(EntityOptionRow(7, "Name", createdAt = 123, noteCount = 4, coLinked = true))
            .toEntityOptions()
            .single()

        assertEquals(EntityOption(7, "Name", createdAt = 123, noteCount = 4, coLinked = true), option)
    }

    @Test
    fun noRows_noOptions() {
        assertEquals(emptyList<EntityOption>(), emptyList<EntityOptionRow>().toEntityOptions())
    }

    private fun row(id: Long, name: String, coLinked: Boolean = false) =
        EntityOptionRow(id, name, createdAt = 0, noteCount = 0, coLinked = coLinked)
}
