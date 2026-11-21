package com.doro.organizer.ui.notes

import androidx.annotation.StringRes
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective

/** A secondary filter of a list, in the order of its chip (ui.md § 3.2, § 3.4). */
enum class FilterDimension { Person, Project, Date }

/**
 * Everything that differs between the five perspectives (ui.md § 3.2): the tab label, the title when no entity name
 * replaces it, the §2 "Secondary Filters Allowed" column (DEC-28), and whether the Date chip offers "Undated" and the
 * screen shows `MonthGrid` (DEC-53). It holds no state.
 */
data class PerspectiveSpec(
    val perspective: Perspective,
    @param:StringRes val tabLabel: Int,
    @param:StringRes val title: Int,
    val primaryKind: EntityKind?,
    val filters: List<FilterDimension>,
    val offersUndated: Boolean,
    val showsMonthGrid: Boolean,
) {
    override fun toString() = "PerspectiveSpec($perspective)"

    companion object {
        fun of(p: Perspective): PerspectiveSpec = when (p) {
            Perspective.All -> PerspectiveSpec(
                perspective = p,
                tabLabel = R.string.tab_all,
                title = R.string.title_all,
                primaryKind = null,
                filters = listOf(FilterDimension.Person, FilterDimension.Project, FilterDimension.Date),
                offersUndated = true,
                showsMonthGrid = false,
            )
            Perspective.Individual -> PerspectiveSpec(
                perspective = p,
                tabLabel = R.string.tab_people,
                title = R.string.tab_people,
                primaryKind = EntityKind.Person,
                filters = listOf(FilterDimension.Project, FilterDimension.Date),
                offersUndated = true,
                showsMonthGrid = false,
            )
            Perspective.Project -> PerspectiveSpec(
                perspective = p,
                tabLabel = R.string.tab_projects,
                title = R.string.tab_projects,
                primaryKind = EntityKind.Project,
                filters = listOf(FilterDimension.Person, FilterDimension.Date),
                offersUndated = true,
                showsMonthGrid = false,
            )
            Perspective.Date -> PerspectiveSpec(
                perspective = p,
                tabLabel = R.string.tab_calendar,
                title = R.string.tab_calendar,
                primaryKind = null,
                filters = listOf(FilterDimension.Person, FilterDimension.Project),
                offersUndated = false,
                showsMonthGrid = true,
            )
            Perspective.Orphan -> PerspectiveSpec(
                perspective = p,
                tabLabel = R.string.tab_orphans,
                title = R.string.tab_orphans,
                primaryKind = null,
                filters = listOf(FilterDimension.Date),
                offersUndated = true,
                showsMonthGrid = false,
            )
        }
    }
}
