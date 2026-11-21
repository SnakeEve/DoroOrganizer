package com.doro.organizer.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import com.doro.organizer.R
import com.doro.organizer.model.Perspective

/** A navigation item of the shell (ui.md § 2.1): its label and its unselected and selected icon. */
class TabItem(@param:StringRes val label: Int, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int)

/**
 * The item of each tab in §2 order. Notes and Orphans have one icon for both states: Material Symbols has no distinct
 * filled form of `notes` or `link_off`, so the M3 indicator and the selected semantics carry the state (ui.md § 2.1).
 */
fun Perspective.tabItem(): TabItem = when (this) {
    Perspective.All -> TabItem(R.string.tab_all, R.drawable.ic_notes, R.drawable.ic_notes)
    Perspective.Individual -> TabItem(R.string.tab_people, R.drawable.ic_group, R.drawable.ic_group_filled)
    Perspective.Project -> TabItem(R.string.tab_projects, R.drawable.ic_folder, R.drawable.ic_folder_filled)
    Perspective.Date ->
        TabItem(R.string.tab_calendar, R.drawable.ic_calendar_month, R.drawable.ic_calendar_month_filled)
    Perspective.Orphan -> TabItem(R.string.tab_orphans, R.drawable.ic_link_off, R.drawable.ic_link_off)
}

/**
 * The layout of the navigation suite (DEC-50): none while the top entry is not a tab root, so the editor, pickers and
 * Settings cover the window; otherwise the bar at compact width and the rail at every wider width, whatever the
 * height or posture (a phone in landscape shows the rail).
 */
fun navigationSuiteTypeOf(topIsTabRoot: Boolean, compactWidth: Boolean): NavigationSuiteType = when {
    !topIsTabRoot -> NavigationSuiteType.None
    compactWidth -> NavigationSuiteType.NavigationBar
    else -> NavigationSuiteType.NavigationRail
}
