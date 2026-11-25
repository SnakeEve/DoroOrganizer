package com.doro.organizer.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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

/** What a row's tap and long-press do (ui.md § 3.8): outside selection mode open the note or enter the mode. */
internal class RowActions(
    val open: (noteId: Long) -> Unit,
    val enter: (noteId: Long) -> Unit,
    val toggle: (noteId: Long) -> Unit,
)

/**
 * The list's side of the position rules (ui.md § 3.11, DEC-110): the screen's [reveal] of a note just created, and the
 * row kept across a return, which the list takes once when it starts ([takeKeptRow]) and hands back when it leaves
 * composition ([keepRow]).
 */
internal class ListPositionHooks(
    val reveal: NewNoteReveal,
    val takeKeptRow: () -> KeptRow?,
    val keepRow: (KeptRow?) -> Unit,
)

/**
 * The list area (ui.md § 3.6): loading, "(No data)" or the rows in the order the query gave them; the UI never sorts,
 * groups or reorders (DEC-23). With [header] (the Date perspective's grid and selection header, or nothing in its
 * end pane) the loading and empty states are list items, top-aligned under it; without, they are centered;
 * [headerItems] counts the items [header] adds before the rows. In selection mode ([NoteListUiState.selection]) every
 * row shows whether it is selected. [bottomPadding] keeps the last row clear of the create button (ui.md § 2.3). The
 * position rules of ui.md § 3.11 act through [position].
 */
@Composable
internal fun NoteListBody(
    state: NoteListUiState,
    listState: LazyListState,
    formats: DateFormats,
    onCheckedChange: RowToggle,
    rowActions: RowActions,
    onClearFilters: () -> Unit,
    bottomPadding: Dp,
    position: ListPositionHooks,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
    headerItems: Int = 0,
) {
    val anchor = remember { ToggleAnchor() }
    ListPositionRules(state, listState, anchor, position, headerItems)
    val toggle = { row: NoteRowUi, checked: Boolean ->
        if (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == row.id) {
            val before = ScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            anchor.record(row.id, checked, before, state.rows)
        }
        onCheckedChange(row, checked) { changed -> if (!changed) anchor.drop(row.id) }
    }
    when {
        header == null && state.loading -> DelayedProgress(modifier.fillMaxSize())
        header == null && state.rows.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(state.filtersActive, onClearFilters)
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize().testTag("note_list"),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            header?.invoke(this)
            when {
                state.loading -> item(key = "loading") { DelayedProgress(Modifier.fillMaxWidth().heightIn(min = 96.dp)) }
                state.rows.isEmpty() -> item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
                        EmptyState(state.filtersActive, onClearFilters)
                    }
                }
                else -> items(state.rows, key = { it.id }) { row ->
                    val selected = state.selection?.let { row.id in it }
                    NoteRowItem(
                        row = row,
                        formats = formats,
                        onCheckedChange = { toggle(row, it) },
                        onLongClick = { if (selected == null) rowActions.enter(row.id) else rowActions.toggle(row.id) },
                        onClick = { if (selected == null) rowActions.open(row.id) else rowActions.toggle(row.id) },
                        modifier = Modifier.animateItem(),
                        selected = selected,
                    )
                }
            }
        }
    }
}

/**
 * The position rules (ui.md § 3.9 item 4, § 3.11; DEC-110), in the composition that hands [state]'s rows to the
 * `LazyColumn`, so the rows and the position land in the same frame: `requestScrollToItem` overrides key anchoring for
 * that one remeasure. Every helper sees every rows value, so each ends or updates its own state; the first position
 * wins: the reveal, then the toggle anchor, then the top anchor (at the very top) or the keep anchor (below it).
 */
@Composable
private fun ListPositionRules(
    state: NoteListUiState,
    listState: LazyListState,
    toggleAnchor: ToggleAnchor,
    position: ListPositionHooks,
    headerItems: Int,
) {
    val topAnchor = remember { TopAnchor() }
    // Read before the LazyColumn's first layout of this composition: the restored position (DEC-110 (3)).
    val keepAnchor = remember { KeepAnchor(position.takeKeptRow(), LastLayout.of(listState).scrollPosition) }
    val rows = state.rows
    val layout = LastLayout.of(listState)
    val revealed = position.reveal.positionFor(rows, state.rowsRead, layout, headerItems)
    val toggled = toggleAnchor.positionFor(rows)
    val top = topAnchor.positionFor(rows, layout)
    val kept = keepAnchor.positionFor(rows, layout, headerItems)
    (revealed ?: toggled ?: top ?: kept)?.let { listState.requestScrollToItem(it.index, it.offset) }

    val currentRows by rememberUpdatedState(rows)
    val keepRow by rememberUpdatedState(position.keepRow)
    DisposableEffect(listState) {
        onDispose { keepRow(keepAnchor.unusedSeed() ?: KeptRow.of(LastLayout.of(listState), currentRows)) }
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
