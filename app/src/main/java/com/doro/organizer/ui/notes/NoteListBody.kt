package com.doro.organizer.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.ui.common.DateFormats
import com.doro.organizer.ui.common.DelayedProgress
import com.doro.organizer.ui.common.NoteRowItem
import com.doro.organizer.ui.common.NoteRowUi

/**
 * A done toggle of a row (ui.md § 3.9): sets [checked] on the row's note and reports through `onDone` whether the
 * row changed.
 */
internal typealias RowToggle = (row: NoteRowUi, checked: Boolean, onDone: (changed: Boolean) -> Unit) -> Unit

/**
 * The list area (ui.md § 3.6): loading, "(No data)" or the rows in the order the query gave them; the UI never sorts,
 * groups or reorders (DEC-23). With [header] (the Date perspective's grid and selection header, or nothing in its
 * end pane) the loading and empty states are list items, top-aligned under it; without, they are centered.
 */
@Composable
internal fun NoteListBody(
    state: NoteListUiState,
    listState: LazyListState,
    formats: DateFormats,
    onCheckedChange: RowToggle,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
) {
    val anchor = remember { ToggleAnchor() }
    // ui.md § 3.9 item 4: in the composition that hands the toggle's emission to the list, so the data change and
    // the restored position land in the same frame and the viewport stays while the row animates away.
    anchor.positionFor(state.rows)?.let { listState.requestScrollToItem(it.index, it.offset) }
    val toggle = { row: NoteRowUi, checked: Boolean ->
        if (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == row.id) {
            val position = ScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            anchor.record(row.id, checked, position, state.rows)
        }
        onCheckedChange(row, checked) { changed -> if (!changed) anchor.drop(row.id) }
    }
    when {
        header == null && state.loading -> DelayedProgress(modifier.fillMaxSize())
        header == null && state.rows.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(state.filtersActive, onClearFilters)
        }
        else -> LazyColumn(modifier.fillMaxSize().testTag("note_list"), state = listState) {
            header?.invoke(this)
            when {
                state.loading -> item(key = "loading") { DelayedProgress(Modifier.fillMaxWidth().heightIn(min = 96.dp)) }
                state.rows.isEmpty() -> item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
                        EmptyState(state.filtersActive, onClearFilters)
                    }
                }
                else -> items(state.rows, key = { it.id }) { row ->
                    NoteRowItem(row, formats, onCheckedChange = { toggle(row, it) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

/** "(No data)" (verbatim §5, DEC-25), and "Clear filters" while a secondary filter or the search is active. */
@Composable
private fun EmptyState(filtersActive: Boolean, onClearFilters: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(R.string.no_data),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filtersActive) {
            TextButton(onClick = onClearFilters) { Text(stringResource(R.string.action_clear_filters)) }
        }
    }
}
