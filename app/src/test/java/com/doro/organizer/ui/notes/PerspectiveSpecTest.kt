package com.doro.organizer.ui.notes

import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.notes.FilterDimension.Date
import com.doro.organizer.ui.notes.FilterDimension.Person
import com.doro.organizer.ui.notes.FilterDimension.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table of ui.md § 3.2: tab labels (Q10), titles, the §2 "Secondary Filters Allowed" column (DEC-28) and the Date
 * specifics.
 */
class PerspectiveSpecTest {
    @Test
    fun everyPerspective_matchesTheTable() {
        val expected = listOf(
            PerspectiveSpec(
                Perspective.All, R.string.tab_all, R.string.title_all, null, listOf(Person, Project, Date), true, false,
            ),
            PerspectiveSpec(
                Perspective.Individual, R.string.tab_people, R.string.tab_people, EntityKind.Person,
                listOf(Project, Date), true, false,
            ),
            PerspectiveSpec(
                Perspective.Project, R.string.tab_projects, R.string.tab_projects, EntityKind.Project,
                listOf(Person, Date), true, false,
            ),
            PerspectiveSpec(
                Perspective.Date, R.string.tab_calendar, R.string.tab_calendar, null, listOf(Person, Project), false, true,
            ),
            PerspectiveSpec(
                Perspective.Orphan, R.string.tab_orphans, R.string.tab_orphans, null, listOf(Date), true, false,
            ),
        )

        assertEquals(expected, Perspective.entries.map(PerspectiveSpec::of))
    }

    @Test
    fun noPerspective_offersItsPrimaryDimensionAsAChip() {
        for (p in Perspective.entries) {
            val spec = PerspectiveSpec.of(p)
            val primary = when (spec.primaryKind) {
                EntityKind.Person -> Person
                EntityKind.Project -> Project
                null -> if (spec.showsMonthGrid) Date else null
            }
            assertTrue("$p offers its primary as a chip", primary == null || primary !in spec.filters)
        }
    }
}
