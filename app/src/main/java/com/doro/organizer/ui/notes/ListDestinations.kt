package com.doro.organizer.ui.notes

import com.doro.organizer.model.EntityKind
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.NotePrefill
import com.doro.organizer.ui.navigation.PickerKey
import com.doro.organizer.ui.navigation.PickerPurpose

/**
 * The editor of a new note created on a perspective (ui.md § 2.3, § 6.7, DEC-38): prefilled from the primary only,
 * the chosen person of Individual, the chosen project of Project or the selected day of Date; never from a secondary
 * filter, the search or a Date range. Every key here takes its [visit] from `Navigator.nextVisit()` (DEC-100).
 */
internal fun createNoteKey(spec: PerspectiveSpec, primary: Primary, visit: Int): EditorKey {
    val prefill = when (primary) {
        is Primary.Entity -> when (spec.primaryKind) {
            EntityKind.Person -> NotePrefill(personId = primary.id)
            EntityKind.Project -> NotePrefill(projectId = primary.id)
            null -> NotePrefill()
        }
        is Primary.Day -> NotePrefill(epochDay = primary.epochDay)
        else -> NotePrefill()
    }
    return EditorKey(noteId = null, visit = visit, prefill = prefill)
}

/**
 * The picker of a Person or Project chip (ui.md § 3.4, § 4.2): the chip's current entity is chosen, and in
 * Individual and Project the entities co-linked with the primary of the other kind come first (DEC-28).
 */
internal fun filterPickerKey(spec: PerspectiveSpec, primary: Primary, chip: FilterChipUi, visit: Int): PickerKey {
    val kind = when (chip.dimension) {
        FilterDimension.Person -> EntityKind.Person
        FilterDimension.Project -> EntityKind.Project
        FilterDimension.Date -> error("the Date chip opens the date filter sheet")
    }
    val coLinkedTo = (primary as? Primary.Entity)?.id?.takeIf { spec.primaryKind != null && spec.primaryKind != kind }
    return PickerKey(kind, PickerPurpose.SecondaryFilter((chip.value as? ChipValue.Entity)?.id, coLinkedTo), visit)
}

/** The picker of the Individual or Project title button (ui.md § 4.2): the current entity is chosen. */
internal fun primaryPickerKey(spec: PerspectiveSpec, primary: Primary.Entity, visit: Int): PickerKey =
    PickerKey(checkNotNull(spec.primaryKind), PickerPurpose.PerspectivePrimary(primary.id), visit)
