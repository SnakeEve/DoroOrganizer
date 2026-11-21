package com.doro.organizer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.doro.organizer.R
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.common.AppSnackbarHost

/**
 * The root entry of a tab: its titled top app bar and the shell's snackbar host (ui.md § 2.2, § 2.4). The note list
 * of the perspective, its search and Settings actions and the create button belong to `NoteListScreen` (SPEC T16),
 * which replaces this entry's content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabRootScreen(perspective: Perspective) {
    val title = stringResource(perspective.titleRes())
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize())
    }
}

/** The title of a perspective without a chosen entity (ui.md § 4). */
@StringRes
private fun Perspective.titleRes(): Int = when (this) {
    Perspective.All -> R.string.title_all
    Perspective.Individual -> R.string.tab_people
    Perspective.Project -> R.string.tab_projects
    Perspective.Date -> R.string.tab_calendar
    Perspective.Orphan -> R.string.tab_orphans
}
