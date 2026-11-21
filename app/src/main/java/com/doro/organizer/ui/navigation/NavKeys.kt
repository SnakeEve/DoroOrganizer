package com.doro.organizer.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.doro.organizer.model.Perspective
import kotlinx.serialization.Serializable

// Keys hold ids and enums only, never text (DEC-64, DEC-70). They keep the generated toString(): Navigation 3 derives
// each entry's contentKey from it, so the tab roots of different epochs never share saved state or ViewModels
// (architecture.md § Keys and stacks).

/** The root of a tab; [epoch] is the `dataEpoch` it was created in (architecture.md § Data reset). */
@Serializable
data class TabKey(val perspective: Perspective, val epoch: Int) : NavKey
