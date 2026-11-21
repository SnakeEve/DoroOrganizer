package com.doro.organizer.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import kotlinx.serialization.Serializable

// Keys hold ids and enums only, never text (DEC-64, DEC-70). They keep the generated toString(): Navigation 3 derives
// each entry's contentKey from it, so the tab roots of different epochs never share saved state or ViewModels
// (architecture.md § Keys and stacks).

/** The root of a tab; [epoch] is the `dataEpoch` it was created in (architecture.md § Data reset). */
@Serializable
data class TabKey(val perspective: Perspective, val epoch: Int) : NavKey

/**
 * The note editor (ui.md § 6): an existing note, or with [noteId] null a new one started from [prefill] (DEC-38).
 * [visit] comes from `Navigator.nextVisit()`, so every opening is its own entry; it has no default value, because a
 * default would not be saved (DEC-100).
 */
@Serializable
data class EditorKey(val noteId: Long?, val visit: Int, val prefill: NotePrefill = NotePrefill()) : NavKey

/** What a new note starts with: the perspective's primary only (ui.md § 6.7, DEC-38). */
@Serializable
data class NotePrefill(val personId: Long? = null, val projectId: Long? = null, val epochDay: Long? = null)

/**
 * The full-screen `EntityPicker` (ui.md § 7) for [kind]; for `BatchAssociate` [kind] is the first tab. [visit] as in
 * [EditorKey] (DEC-100).
 */
@Serializable
data class PickerKey(val kind: EntityKind, val purpose: PickerPurpose, val visit: Int) : NavKey

/**
 * The Settings screen (ui.md § 9), opened from the gear action of any perspective (DEC-51). [startBackup]: opened by
 * "Back up now" of the kekBroken notice, which runs "Backup all data" there (ui.md § 2.4).
 */
@Serializable
data class SettingsKey(val startBackup: Boolean = false) : NavKey
