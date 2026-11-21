package com.doro.organizer.ui.notes

import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.NotePrefill
import com.doro.organizer.ui.navigation.PickerKey
import com.doro.organizer.ui.navigation.PickerPurpose
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a list sends the user (ui.md § 2.3, § 3.4, § 4.2, § 6.7): a new note prefilled from the primary only (DEC-38),
 * the chip pickers with the chip's entity chosen and the co-linked order of DEC-28, and the title's picker; each key
 * carries the `visit` it was given (DEC-100).
 */
class ListDestinationsTest {
    private val all = PerspectiveSpec.of(Perspective.All)
    private val individual = PerspectiveSpec.of(Perspective.Individual)
    private val project = PerspectiveSpec.of(Perspective.Project)
    private val date = PerspectiveSpec.of(Perspective.Date)

    @Test
    fun aNewNote_isPrefilledFromThePrimaryOnly() {
        assertEquals(
            EditorKey(null, visit = 1, prefill = NotePrefill(personId = 4)),
            createNoteKey(individual, Primary.Entity(4, "Alice"), visit = 1),
        )
        assertEquals(
            EditorKey(null, visit = 2, prefill = NotePrefill(projectId = 5)),
            createNoteKey(project, Primary.Entity(5, "Alpha"), visit = 2),
        )
        assertEquals(
            EditorKey(null, visit = 3, prefill = NotePrefill(epochDay = 20_774)),
            createNoteKey(date, Primary.Day(20_774), visit = 3),
        )
    }

    @Test
    fun aRange_noChosenEntity_AllAndOrphan_prefillNothing() {
        val empty = EditorKey(null, visit = 1, prefill = NotePrefill())

        assertEquals(empty, createNoteKey(date, Primary.Range(20_770, 20_774), visit = 1))
        assertEquals(empty, createNoteKey(individual, Primary.None, visit = 1))
        assertEquals(empty, createNoteKey(all, Primary.None, visit = 1))
        assertEquals(empty, createNoteKey(PerspectiveSpec.of(Perspective.Orphan), Primary.None, visit = 1))
    }

    @Test
    fun theChipPickers_chooseTheChipsEntity_andPutTheOtherKindsPrimaryCoLinkedFirst() {
        val projectChip = FilterChipUi(FilterDimension.Project, ChipValue.Entity(8, "Alpha"))
        val personChip = FilterChipUi(FilterDimension.Person, ChipValue.NotSet)

        assertEquals(
            PickerKey(EntityKind.Project, PickerPurpose.SecondaryFilter(selectedId = 8, coLinkedTo = 4), visit = 1),
            filterPickerKey(individual, Primary.Entity(4, "Alice"), projectChip, visit = 1),
        )
        assertEquals(
            PickerKey(EntityKind.Person, PickerPurpose.SecondaryFilter(selectedId = null, coLinkedTo = 5), visit = 2),
            filterPickerKey(project, Primary.Entity(5, "Alpha"), personChip, visit = 2),
        )
        assertEquals(
            PickerKey(EntityKind.Person, PickerPurpose.SecondaryFilter(selectedId = null, coLinkedTo = null), visit = 3),
            filterPickerKey(all, Primary.None, personChip, visit = 3),
        )
        assertEquals(
            PickerKey(EntityKind.Project, PickerPurpose.SecondaryFilter(selectedId = 8, coLinkedTo = null), visit = 4),
            filterPickerKey(date, Primary.Day(20_774), projectChip, visit = 4),
        )
    }

    @Test
    fun theTitlesPicker_choosesTheCurrentEntity() {
        assertEquals(
            PickerKey(EntityKind.Project, PickerPurpose.PerspectivePrimary(5), visit = 1),
            primaryPickerKey(project, Primary.Entity(5, "Alpha"), visit = 1),
        )
    }

    @Test
    fun theDateChip_hasNoPicker() {
        val chip = FilterChipUi(FilterDimension.Date, ChipValue.Date(DateFilter.Undated))

        val failure = runCatching { filterPickerKey(all, Primary.None, chip, visit = 1) }.exceptionOrNull()

        assertEquals(IllegalStateException::class, failure?.let { it::class })
    }
}
