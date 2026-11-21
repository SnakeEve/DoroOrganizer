package com.doro.organizer.ui.navigation

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import com.doro.organizer.R
import com.doro.organizer.model.Perspective
import org.junit.Assert.assertEquals
import org.junit.Test

/** The navigation suite (ui.md § 2.1, DEC-50). */
class TabsTest {
    @Test
    fun fiveTabs_inSpecOrder_withTheirLabels() {
        assertEquals(
            listOf(R.string.tab_all, R.string.tab_people, R.string.tab_projects, R.string.tab_calendar, R.string.tab_orphans),
            Perspective.entries.map { it.tabItem().label },
        )
    }

    @Test
    fun selectedIcons_areFilled_whereMaterialSymbolsHasAFilledForm() {
        assertEquals(R.drawable.ic_group_filled, Perspective.Individual.tabItem().selectedIcon)
        assertEquals(R.drawable.ic_folder_filled, Perspective.Project.tabItem().selectedIcon)
        assertEquals(R.drawable.ic_calendar_month_filled, Perspective.Date.tabItem().selectedIcon)
        assertEquals(R.drawable.ic_notes, Perspective.All.tabItem().selectedIcon)
        assertEquals(R.drawable.ic_link_off, Perspective.Orphan.tabItem().selectedIcon)
    }

    @Test
    fun layout_isTheBarAtCompactWidth_andTheRailOtherwise() {
        assertEquals(NavigationSuiteType.NavigationBar, navigationSuiteTypeOf(topIsTabRoot = true, compactWidth = true))
        assertEquals(NavigationSuiteType.NavigationRail, navigationSuiteTypeOf(topIsTabRoot = true, compactWidth = false))
    }

    @Test
    fun layout_isNone_whileTheTopEntryIsNotATabRoot() {
        assertEquals(NavigationSuiteType.None, navigationSuiteTypeOf(topIsTabRoot = false, compactWidth = true))
        assertEquals(NavigationSuiteType.None, navigationSuiteTypeOf(topIsTabRoot = false, compactWidth = false))
    }
}
