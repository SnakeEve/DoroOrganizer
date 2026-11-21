package com.doro.organizer.ui.common

import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity

/**
 * The result key of every full-screen `EntityPicker` on the Navigation 3 result bus (architecture.md § Keys and
 * stacks). One key serves all openers: the picker pops itself right after sending, so the entry that opened it is the
 * only one composed to receive it (ui.md § 7.7).
 */
const val PICKER_RESULT = "PickerResult"

/** What a full-screen `EntityPicker` returns (ui.md § 7.7); every case prints kinds and counts only (DEC-70). */
sealed interface PickerResult {
    /** `EditorLinks`: the checked saved [ids] of [kind] and the entities [created] in this visit (DEC-33). */
    data class Links(val kind: EntityKind, val ids: Set<Long>, val created: List<NewEntity>) : PickerResult {
        override fun toString() = "Links($kind, ids=${ids.size}, created=${created.size})"
    }

    /** `BatchAssociate`: both kinds, so the list makes one `associate` call (DEC-36). */
    data class Associate(
        val personIds: Set<Long>,
        val projectIds: Set<Long>,
        val newPersons: List<NewEntity>,
        val newProjects: List<NewEntity>,
    ) : PickerResult {
        override fun toString() = "Associate(personIds=${personIds.size}, projectIds=${projectIds.size}, " +
            "newPersons=${newPersons.size}, newProjects=${newProjects.size})"
    }

    /** `PerspectivePrimary`: the chosen entity. */
    data class Primary(val kind: EntityKind, val id: Long) : PickerResult {
        override fun toString() = "Primary($kind, id=$id)"
    }

    /** `SecondaryFilter`: the chosen entity, or null for "Any person" / "Any project". */
    data class Filter(val kind: EntityKind, val id: Long?) : PickerResult {
        override fun toString() = "Filter($kind, id=$id)"
    }

    /** `DeleteEntity`: the entity to delete after the impact dialog (ui.md § 6.11). */
    data class Delete(val kind: EntityKind, val id: Long) : PickerResult {
        override fun toString() = "Delete($kind, id=$id)"
    }
}
