package com.doro.organizer.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.doro.organizer.R

/**
 * The contextual bar of selection mode (ui.md § 5.2), which replaces the app bar or the search bar: "Exit selection",
 * the count as a polite live region, "Select all" and the menu with "Set date…" and "Delete". Its actions are
 * disabled while [enabled] is false, when a batch action runs (§ 5.3); the exit stays available. "Associate" needs
 * the batch associate picker (`PickerKey`, SPEC T18), which brings its button to `NoteListViewModel.associate`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    enabled: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onSetDate: () -> Unit,
    onDelete: () -> Unit,
) {
    // UI-local: an open menu is not restored after a process death (ui.md § 3.10).
    var menuOpen by remember { mutableStateOf(false) }
    val container = MaterialTheme.colorScheme.secondaryContainer
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_exit_selection))
            }
        },
        title = {
            Text(
                text = pluralStringResource(R.plurals.selected_count, count, count),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(onClick = onSelectAll, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.action_select_all))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen && enabled, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_set_date)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit_calendar), contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSetDate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        // The same container scrolled or not: the mode change stays visible beyond the text.
        colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
    )
}
