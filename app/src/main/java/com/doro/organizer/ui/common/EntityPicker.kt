package com.doro.organizer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind

/**
 * The single-select [EntityPicker] embedded as the body of Individual and Project while nothing is chosen (ui.md
 * § 4.2, § 7, DEC-52): the name search field over every entity of [kind]; a tap on a row calls [onPick]. There is no
 * app bar, no "Any" row and no create row: persons and projects are created only from a note (§3). [search] is the
 * field's text (`EntityPickerViewModel.search`); [searchFocus] lets the perspective's Search action move focus into
 * the field (ui.md § 2.2).
 */
@Composable
fun EntityPicker(
    kind: EntityKind,
    search: String,
    state: EntityPickerUiState,
    onSearchChange: (String) -> Unit,
    onPick: (Long) -> Unit,
    searchFocus: FocusRequester,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PickerSearchField(kind, search, onSearchChange, searchFocus)
        val formats = rememberDateFormats()
        when {
            state.loading -> DelayedProgress(Modifier.fillMaxSize())
            state.rows.isEmpty() -> PickerEmpty(hint = if (state.anyEntity) null else emptyHint(kind))
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.rows, key = { it.id }) { row ->
                    ListItem(
                        modifier = Modifier
                            .testTag("picker_row_${row.id}")
                            .heightIn(min = 56.dp)
                            .selectable(selected = false, role = Role.Button, onClick = { onPick(row.id) }),
                        headlineContent = {
                            Text(
                                text = row.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                pluralStringResource(
                                    R.plurals.picker_entity_meta,
                                    row.noteCount,
                                    row.noteCount,
                                    formats.medium(row.added),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerSearchField(
    kind: EntityKind,
    text: String,
    onTextChange: (String) -> Unit,
    focus: FocusRequester,
) {
    // Always wrapped: the keyboard must never learn names (DEC-71, Q11).
    NoPersonalizedLearning {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focus)
                .testTag("picker_search"),
            placeholder = { Text(stringResource(searchHint(kind))) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = if (text.isNotEmpty()) {
                {
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear_search))
                    }
                }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

/** "(No data)", with the hint how entities come to exist when there are none at all (ui.md § 7.8). */
@Composable
private fun PickerEmpty(hint: Int?) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.no_data),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint?.let {
            Text(
                stringResource(it),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun searchHint(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_search_people
    EntityKind.Project -> R.string.picker_search_projects
}

private fun emptyHint(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_empty_people_hint
    EntityKind.Project -> R.string.picker_empty_projects_hint
}
