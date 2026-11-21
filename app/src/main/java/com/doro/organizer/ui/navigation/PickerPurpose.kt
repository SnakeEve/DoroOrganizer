package com.doro.organizer.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Why a full-screen `EntityPicker` is open (ui.md § 7.1): it decides the title, the mode, the first rows and the
 * result. The cases carry ids only, never names (architecture.md § Keys and stacks, DEC-70).
 */
@Serializable
sealed interface PickerPurpose {
    /** The editor's "Add person" / "Add project": multi-select, starting with the draft's saved links checked. */
    @Serializable
    data class EditorLinks(val selectedIds: List<Long>) : PickerPurpose

    /** The contextual bar's "Associate": multi-select over both kinds on two tabs, starting empty (DEC-36). */
    @Serializable
    data object BatchAssociate : PickerPurpose

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
