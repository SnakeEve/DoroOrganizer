package com.doro.organizer.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Why a full-screen `EntityPicker` is open (ui.md § 7.1): it decides the title, the mode, the first rows and the
 * result. The cases carry ids only, never names (architecture.md § Keys and stacks, DEC-70).
 */
@Serializable
sealed interface PickerPurpose {
    /**
     * The editor's "Add person" / "Add project": multi-select, starting with the draft's saved links checked. For Add
     * person, [coLinkedTo] are the draft's stored project ids, sorted, [exceptNoteId] its note (null for a note never
     * inserted or with no stored project) and [projectCount] its projects, stored and created in this edit; with
     * [projectCount] above 0 the people of the note's projects are listed first (DEC-111). Add project passes an empty
     * list, null and 0 (Q20).
     */
    @Serializable
    data class EditorLinks(
        val selectedIds: List<Long>,
        val coLinkedTo: List<Long>,
        val exceptNoteId: Long?,
        val projectCount: Int,
    ) : PickerPurpose

    /**
     * The contextual bar's "Associate": multi-select over both kinds on two tabs, starting empty (DEC-36).
     * [selectionSaved]: the list's selection fits its saved state (DEC-57); when it does not, Android closing the app
     * loses it, and the picker that comes back closes. [coLinkedTo]: the project chosen in the Project perspective,
     * whose people the People tab lists first (DEC-111), else null.
     */
    @Serializable
    data class BatchAssociate(val selectionSaved: Boolean, val coLinkedTo: Long?) : PickerPurpose

    /** The Individual or Project title: single-select; [selectedId] is the current entity. */
    @Serializable
    data class PerspectivePrimary(val selectedId: Long?) : PickerPurpose

    /**
     * The Person or Project chip: single-select with "Any …" first; [coLinkedTo] is the primary entity of the
     * other kind whose co-linked entities come first (DEC-28), else null.
     */
    @Serializable
    data class SecondaryFilter(val selectedId: Long?, val coLinkedTo: Long?) : PickerPurpose

    /** The editor's "Delete person…" / "Delete project…": single-select, the draft's [linkedIds] first (DEC-34). */
    @Serializable
    data class DeleteEntity(val linkedIds: List<Long>) : PickerPurpose
}
