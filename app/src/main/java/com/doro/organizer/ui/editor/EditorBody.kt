package com.doro.organizer.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.common.NoPersonalizedLearning
import com.doro.organizer.ui.common.rememberAnnouncer
import com.doro.organizer.ui.common.rememberDateFormats

/**
 * The editor's scrolling body (ui.md § 6.1, § 6.13): content, association area, date field and done checkbox, at most
 * 720 dp wide and centered; the viewport ends above the keyboard, and the insets the `Scaffold` applied are not added
 * twice. The content field grows with its text up to the viewport's height and then scrolls inside itself
 * ([ContentField]); `BoxWithConstraints` reads the viewport in the measure pass, so the bound follows the keyboard in
 * the same frame.
 */
@Composable
internal fun EditorBody(
    state: EditorUiState,
    vm: EditorViewModel,
    padding: PaddingValues,
    contentFocus: FocusRequester,
    onAddLinks: (EntityKind) -> Unit,
    onOpenDate: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding(),
    ) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ContentField(vm, contentFocus, maxHeight = viewportHeight)
                AssociationArea(state, onRemove = vm::removeLink, onAdd = onAddLinks)
                DateField(state, onOpen = onOpenDate, onRemove = { vm.setDate(null) })
                DoneRow(state.checked, vm)
            }
        }
    }
}

/**
 * One multiline field for title and content (ui.md § 6.3, DEC-39): plain text stored as typed, the placeholder
 * "Title / content" while empty, capped by `ContentCap`; the keyboard never learns from it (DEC-71).
 *
 * The field is at most [maxHeight] high, the viewport's height (DEC-105): a longer text scrolls inside the field,
 * which keeps the caret in view, and hands the rest of a drag or fling to the page (nested scrolling). Unbounded, it
 * would take the height of its whole text, and M3's `TextField` measures its container with a fixed `Constraints` of
 * that height, which Compose cannot represent from 262,143 px on (ui-unit 1.12.1 `Constraints`; material3 1.4.0
 * `TextFieldMeasurePolicy`): from about 3,300 lines at 100 % font on a 3.25x screen, opening, typing into or pasting
 * a long note threw `IllegalArgumentException`. The field still lays out its whole text, so a note above Compose's
 * 16,777,215 px layout limit (more than about 122,000 line breaks at 200 % font) still crashes it (Q14).
 */
@Composable
private fun ContentField(vm: EditorViewModel, focus: FocusRequester, maxHeight: Dp) {
    NoPersonalizedLearning {
        TextField(
            state = vm.content,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .focusRequester(focus)
                .testTag("editor_content"),
            inputTransformation = vm.contentCap,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(stringResource(R.string.content_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
            ),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 6),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/**
 * The association area of §3 (ui.md § 6.4): one traversal group described "Associate persons and projects", people
 * first, each kind a flow of removable chips ending in its "Add" chip.
 */
@Composable
private fun AssociationArea(
    state: EditorUiState,
    onRemove: (EntityKind, LinkChip) -> Unit,
    onAdd: (EntityKind) -> Unit,
) {
    val description = stringResource(R.string.a11y_associate)
    Column(
        modifier = Modifier.semantics {
            contentDescription = description
            isTraversalGroup = true
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinkGroup(EntityKind.Person, state.people, onRemove, onAdd)
        LinkGroup(EntityKind.Project, state.projects, onRemove, onAdd)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkGroup(
    kind: EntityKind,
    chips: List<LinkChip>,
    onRemove: (EntityKind, LinkChip) -> Unit,
    onAdd: (EntityKind) -> Unit,
) {
    val person = kind == EntityKind.Person
    Text(
        text = stringResource(if (person) R.string.assoc_people else R.string.assoc_projects),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(if (person) "editor_people" else "editor_projects"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip -> LinkChipItem(chip) { onRemove(kind, chip) } }
        AssistChip(
            onClick = { onAdd(kind) },
            label = { Text(stringResource(if (person) R.string.action_add_person else R.string.action_add_project)) },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
            },
        )
    }
}

/** A linked entity: its name and a remove icon; the whole chip is one button "Remove Alice" (ui.md § 6.4). */
@Composable
private fun LinkChipItem(chip: LinkChip, onRemove: () -> Unit) {
    val description = stringResource(R.string.a11y_remove_link, chip.name)
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(chip.name, Modifier.widthIn(max = 240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = description
            onClick {
                onRemove()
                true
            }
        },
        trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null, Modifier.size(18.dp)) },
    )
}

/**
 * The date row (ui.md § 6.5): one button "Set or change date" whose state is the date or "No date", plus ", Overdue"
 * for an unchecked note dated before today (DEC-26); "Remove date" only while a date is set.
 */
@Composable
private fun DateField(state: EditorUiState, onOpen: () -> Unit, onRemove: () -> Unit) {
    val formats = rememberDateFormats()
    val colors = MaterialTheme.colorScheme
    val dateText = state.date?.let(formats::medium) ?: stringResource(R.string.date_none)
    val overdue = stringResource(R.string.label_overdue)
    val description = stringResource(R.string.a11y_set_date)
    val stateText = if (state.overdue) dateText + stringResource(R.string.names_separator) + overdue else dateText
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics {
                    contentDescription = description
                    stateDescription = stateText
                }
                .testTag("editor_date"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_event), contentDescription = null)
            // The row's description and state carry the texts, so they are not read twice.
            Text(
                text = dateText,
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.overdue) colors.error else colors.onSurface,
            )
            if (state.overdue) {
                // Text plus icon plus color, never color alone (ui.md § 12.6).
                Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_error), null, Modifier.size(16.dp), tint = colors.error)
                    Text(overdue, style = MaterialTheme.typography.labelLarge, color = colors.error)
                }
            }
        }
        if (state.date != null) {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_remove_date))
            }
        }
    }
}

/**
 * The done checkbox row (ui.md § 6.6): one toggleable node described by the action it takes; a saved note is written
 * at once and the change announced like a list toggle (ui.md § 3.9).
 */
@Composable
private fun DoneRow(checked: Boolean, vm: EditorViewModel) {
    val announcer = rememberAnnouncer(LocalUiGraph.current)
    val markedDone = stringResource(R.string.announce_marked_done)
    val markedNotDone = stringResource(R.string.announce_marked_not_done)
    val description = stringResource(if (checked) R.string.a11y_mark_not_done else R.string.a11y_mark_done)
    val stateText = stringResource(if (checked) R.string.state_done else R.string.state_not_done)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox) { target ->
                vm.setChecked(target) { changed ->
                    if (changed) announcer.announce(if (target) markedDone else markedNotDone)
                }
            }
            .semantics {
                contentDescription = description
                stateDescription = stateText
            }
            .testTag("editor_done"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(R.string.editor_done_label), Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.bodyLarge)
    }
}
