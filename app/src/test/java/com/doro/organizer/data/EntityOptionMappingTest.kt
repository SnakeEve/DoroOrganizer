package com.doro.organizer.data

import com.doro.organizer.data.db.EntityDao
import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.model.EntityOption
import com.doro.organizer.testing.unused
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * testing.md I-23 and I-61 (b), repository part: [toEntityOptions] orders picker options by `Collator` on the name,
 * then by id (DEC-32), whatever their `coLinked` flag (the picker forms the groups, DEC-111), and keeps every field
 * and every duplicate; [observeOptionRows] chooses the statement and cuts the ids it binds.
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
    fun theCoLinkedFlag_reordersNothing() {
        val options = listOf(row(1, "Anna"), row(2, "Zed", coLinked = true)).toEntityOptions()

        assertEquals("a co-linked Zed follows Anna", listOf(1L, 2L), options.map { it.id })
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
        val options = listOf(row(9, "Twin"), row(3, "Twin", coLinked = true), row(5, "Twin")).toEntityOptions()

        assertEquals("duplicates are never merged (§1)", listOf(3L, 5L, 9L), options.map { it.id })
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

    // The statement of observeOptionRows (data-layer.md § Editor loads, pickers and existence probes).

    @Test
    fun noIds_readTheOptionsWithoutCoLinks_andNeverTheCoLinkedStatement() {
        val dao = RecordingEntityDao()

        dao.observeOptionRows(emptyList(), exceptNoteId = 4)

        assertEquals(listOf("observeOptions()"), dao.calls)
    }

    @Test
    fun theIds_areBoundDistinctAndSorted_withTheGivenNote() {
        val dao = RecordingEntityDao()

        dao.observeOptionRows(listOf(5, 3, 5), exceptNoteId = 4)
        dao.observeOptionRows(listOf(8), exceptNoteId = null)

        assertEquals(listOf("observeCoLinkedOptions([3, 5], 4)", "observeCoLinkedOptions([8], null)"), dao.calls)
    }

    @Test
    fun moreThan900Ids_bindThe900Smallest() {
        val dao = RecordingEntityDao()

        dao.observeOptionRows((901L downTo 1L).toList(), exceptNoteId = 4)

        assertEquals(listOf((1L..900L).toList()), dao.bound)
    }

    private fun row(id: Long, name: String, coLinked: Boolean = false) =
        EntityOptionRow(id, name, createdAt = 0, noteCount = 0, coLinked = coLinked)

    /** An [EntityDao] that records which picker statement is asked for, with its bindings. */
    private class RecordingEntityDao : EntityDao by unused() {
        val calls = mutableListOf<String>()
        val bound = mutableListOf<List<Long>>()

        override fun observeOptions(): Flow<List<EntityOptionRow>> {
            calls += "observeOptions()"
            return flowOf(emptyList())
        }

        override fun observeCoLinkedOptions(coLinkedTo: List<Long>, exceptNoteId: Long?): Flow<List<EntityOptionRow>> {
            bound += coLinkedTo
            calls += "observeCoLinkedOptions($coLinkedTo, $exceptNoteId)"
            return flowOf(emptyList())
        }
    }
}
