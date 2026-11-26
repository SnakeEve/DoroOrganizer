package com.doro.organizer.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.ui.navigation.PickerPurpose

/** What the rows of [EntityPicker] do; the multi-select callbacks are unused by the single-select forms. */
class PickerActions(
    val onSearchChange: (String) -> Unit,
    /** A tap in a single-select form; null is "Any person" / "Any project" or "All people" / "All projects". */
    val onPick: (id: Long?) -> Unit,
    val onToggle: (id: Long) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onDiscard: (key: String) -> Unit = {},
)

/**
 * The search field and list of an entity picker (ui.md § 7.2–§ 7.8) for [kind] and [purpose]: the full-screen form's
 * body, or with [purpose] null the embedded body of Individual and Project (DEC-52), which has no "Any" row and no
 * create row because persons and projects are created only from a note (§3). [search] is the field's text
 * (`EntityPickerViewModel.search`); [searchFocus] lets the perspective's Search action move focus into the field (ui.md
 * § 2.2); [contentPadding] keeps the last row clear of a create button (ui.md § 2.3).
 */
@Composable
fun EntityPicker(
    kind: EntityKind,
    purpose: PickerPurpose?,
    search: String,
    state: EntityPickerUiState,
    actions: PickerActions,
    searchFocus: FocusRequester,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Column(modifier) {
        PickerSearchField(kind, search, actions.onSearchChange, searchFocus)
        if (state.loading) {
            DelayedProgress(Modifier.fillMaxSize())
            return@Column
        }
        val formats = rememberDateFormats()
        val empty = state.items.none { it is PickerItem.Entity || it is PickerItem.New }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
            items(state.items, key = { it.key }) { item ->
                when (item) {
                    is PickerItem.AnyEntity -> AnyRow(kind, item.selected) { actions.onPick(null) }
                    PickerItem.AllEntities -> AllRow(kind) { actions.onPick(null) }
                    is PickerItem.Create -> CreateRow(item, actions.onCreate)
                    is PickerItem.New -> NewRow(item, actions.onDiscard)
                    is PickerItem.Header -> HeaderRow(item)
                    is PickerItem.Entity -> EntityRow(item, purpose, formats, actions)
                }
            }
            if (empty) item(key = "empty") { PickerEmpty(emptyHint(kind, purpose, state.anyEntity)) }
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

@Composable
private fun AnyRow(kind: EntityKind, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
        headlineContent = { Text(stringResource(anyLabel(kind)), style = MaterialTheme.typography.bodyLarge) },
        trailingContent = if (selected) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
        } else {
            null
        },
    )
}

/** "All people" / "All projects" (DEC-112): a button that never carries the check mark. */
@Composable
private fun AllRow(kind: EntityKind, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .testTag("picker_all")
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        headlineContent = { Text(stringResource(allLabel(kind)), style = MaterialTheme.typography.bodyLarge) },
    )
}

@Composable
private fun CreateRow(item: PickerItem.Create, onCreate: () -> Unit) {
    val label = stringResource(if (item.another) R.string.picker_create_another else R.string.picker_create, item.name)
    ListItem(
        modifier = Modifier
            .testTag("picker_create")
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onCreate),
        leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
        headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun NewRow(item: PickerItem.New, onDiscard: (String) -> Unit) {
    ListItem(
        modifier = Modifier
            .testTag("picker_new_${item.entity.key}")
            .heightIn(min = 56.dp)
            .toggleable(value = true, role = Role.Checkbox, onValueChange = { onDiscard(item.entity.key) }),
        leadingContent = { Checkbox(checked = true, onCheckedChange = null) },
        headlineContent = { EntityName(item.entity.name) },
        supportingContent = { Text(stringResource(R.string.picker_entity_new)) },
    )
}

@Composable
private fun HeaderRow(item: PickerItem.Header) {
    Text(
        text = stringResource(item.text),
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EntityRow(item: PickerItem.Entity, purpose: PickerPurpose?, formats: DateFormats, actions: PickerActions) {
    val row = item.row
    val base = Modifier
        .testTag("picker_row_${row.id}")
        .heightIn(min = 56.dp)
    val multi = purpose.isMultiSelect
    val chooseLabel = stringResource(R.string.a11y_choose)
    ListItem(
        modifier = when {
            multi -> base.toggleable(value = item.checked, role = Role.Checkbox, onValueChange = { actions.onToggle(row.id) })
            // DeleteEntity rows name what a tap does and carry no check mark (ui.md § 7.3).
            purpose is PickerPurpose.DeleteEntity ->
                base.clickable(onClickLabel = chooseLabel, role = Role.Button) { actions.onPick(row.id) }
            else -> base.selectable(selected = item.checked, role = Role.Button) { actions.onPick(row.id) }
        },
        leadingContent = if (multi) {
            { Checkbox(checked = item.checked, onCheckedChange = null) }
        } else {
            null
        },
        headlineContent = { EntityName(row.name) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.picker_entity_meta, row.noteCount, row.noteCount, formats.medium(row.added)))
        },
        trailingContent = if (!multi && item.checked) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
        } else {
            null
        },
    )
}

@Composable
private fun EntityName(name: String) {
    Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/**
 * The hint under "(No data)" when no entity of [kind] exists (ui.md § 7.8): how to create one where the picker has a
 * create row, how notes create them where it has none, and nothing in `DeleteEntity`, opened from the editor.
 */
private fun emptyHint(kind: EntityKind, purpose: PickerPurpose?, anyEntity: Boolean): Int? = when {
    anyEntity || purpose is PickerPurpose.DeleteEntity -> null
    purpose.isMultiSelect -> when (kind) {
        EntityKind.Person -> R.string.picker_create_hint_person
        EntityKind.Project -> R.string.picker_create_hint_project
    }
    else -> when (kind) {
        EntityKind.Person -> R.string.picker_empty_people_hint
        EntityKind.Project -> R.string.picker_empty_projects_hint
    }
}

private fun searchHint(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_search_people
    EntityKind.Project -> R.string.picker_search_projects
}

private fun anyLabel(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_any_person
    EntityKind.Project -> R.string.picker_any_project
}

private fun allLabel(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_all_people
    EntityKind.Project -> R.string.picker_all_projects
}
