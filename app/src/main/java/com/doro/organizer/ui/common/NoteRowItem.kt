package com.doro.organizer.ui.common

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.doro.organizer.R

private const val PERSON_ICON = "person"
private const val PROJECT_ICON = "project"
private const val GROUP_GAP = "gap"

/**
 * One note of a list (ui.md § 3.7, § 3.8, § 12.1): a merged node that reads [rowDescription], with the done checkbox
 * as its own node and a custom action that does what the checkbox does. The UI never changes the row itself: the
 * toggle writes, and the next emission restyles and moves it (DEC-54, architecture.md § Layering rule 3).
 *
 * [selected] is null outside selection mode; in it, the row shows its selection mark and, when selected, the
 * secondary container (DEC-54). [onClick] is the row tap, null while the row has none: outside selection mode the
 * tap opens the editor, which comes with SPEC T18. [onLongClick] enters selection mode, or in it does what a tap does.
 */
@Composable
fun NoteRowItem(
    row: NoteRowUi,
    formats: DateFormats,
    onCheckedChange: (Boolean) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val description = rowDescription(row, formats)
    val state = rowState(row, selected)
    val markLabel = stringResource(if (row.checked) R.string.a11y_mark_not_done else R.string.a11y_mark_done)
    val selectLabel = stringResource(if (selected == true) R.string.a11y_deselect_note else R.string.a11y_select_note)
    ListItem(
        modifier = modifier
            .testTag("note_row_${row.id}")
            .heightIn(min = 72.dp)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        onClickLabel = selectLabel.takeIf { selected != null },
                        onLongClickLabel = selectLabel,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier.longPressOnly(selectLabel, LocalHapticFeedback.current, rememberUpdatedState(onLongClick))
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                state?.let { stateDescription = it }
                customActions = listOf(CustomAccessibilityAction(markLabel) { onCheckedChange(!row.checked); true })
            },
        leadingContent = { DoneCheckbox(row, onCheckedChange) },
        headlineContent = { RowTitle(row) },
        supportingContent = if (row.body != null || hasMetadata(row)) {
            { RowSupporting(row, formats) }
        } else {
            null
        },
        trailingContent = selected?.let { { SelectionMark(it) } },
        colors = if (selected == true) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
    )
}

/**
 * A long-press with its haptic and its accessibility action, and no tap: a row outside selection mode before the
 * editor exists, which must not offer a click that does nothing. Temporary: SPEC T18 gives every row its tap, and
 * this branch goes with the null `onClick`.
 */
private fun Modifier.longPressOnly(label: String, haptics: HapticFeedback, action: State<() -> Unit>): Modifier =
    pointerInput(haptics) {
        detectTapGestures(
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                action.value()
            },
        )
    }.semantics {
        onLongClick(label) {
            action.value()
            true
        }
    }

/**
 * The row's state description (ui.md § 12.1): "Done" for a checked note; in selection mode "Selected" or "Not
 * selected", followed by ", Done" for a checked note; none for an unchecked note outside selection mode.
 */
@Composable
private fun rowState(row: NoteRowUi, selected: Boolean?): String? {
    val done = stringResource(R.string.state_done).takeIf { row.checked }
    val selection = selected?.let { stringResource(if (it) R.string.state_selected else R.string.state_not_selected) }
    return listOfNotNull(selection, done).ifEmpty { null }?.joinToString(stringResource(R.string.names_separator))
}

/** The trailing mark of selection mode (ui.md § 3.7): decorative, the row's state description carries it. */
@Composable
private fun SelectionMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Icon(
        painter = painterResource(if (selected) R.drawable.ic_check_circle_filled else R.drawable.ic_radio_button_unchecked),
        contentDescription = null,
        modifier = Modifier.size(24.dp).clearAndSetSemantics { },
        tint = if (selected) colors.primary else colors.outline,
    )
}

/** The leading checkbox: always enabled, its own node, described by the action it takes (§4, DEC-54, DEC-55). */
@Composable
private fun DoneCheckbox(row: NoteRowUi, onCheckedChange: (Boolean) -> Unit) {
    val action = stringResource(if (row.checked) R.string.a11y_mark_not_done else R.string.a11y_mark_done)
    val state = stringResource(if (row.checked) R.string.state_done else R.string.state_not_done)
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Checkbox(
            checked = row.checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .testTag("note_checkbox_${row.id}")
                .semantics {
                    contentDescription = action
                    stateDescription = state
                },
        )
    }
}

@Composable
private fun RowTitle(row: NoteRowUi) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = row.title ?: stringResource(R.string.no_data),
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.titleMedium,
        color = if (row.title == null || row.checked) colors.onSurfaceVariant else colors.onSurface,
        fontStyle = if (row.title == null) FontStyle.Italic else null,
        textDecoration = if (row.checked) TextDecoration.LineThrough else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun hasMetadata(row: NoteRowUi) =
    row.date != null || row.orphan || row.people.isNotEmpty() || row.projects.isNotEmpty()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowSupporting(row: NoteRowUi, formats: DateFormats) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelLarge
    Column {
        row.body?.let { body ->
            Text(
                text = body,
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasMetadata(row)) {
            FlowRow(
                modifier = Modifier.clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                row.date?.let { date ->
                    Text(
                        text = formats.medium(date),
                        style = labelStyle,
                        color = if (row.overdue) colors.error else colors.onSurfaceVariant,
                    )
                }
                if (row.overdue) {
                    // Text plus icon plus color, never color alone (ui.md § 12.6).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_error), null, Modifier.size(16.dp), tint = colors.error)
                        Text(stringResource(R.string.label_overdue), style = labelStyle, color = colors.error)
                    }
                }
                if (row.orphan) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tint = colors.onSurfaceVariant
                        Icon(painterResource(R.drawable.ic_link_off), null, Modifier.size(16.dp), tint = tint)
                        Text(stringResource(R.string.label_orphaned), style = labelStyle, color = tint)
                    }
                } else if (row.people.isNotEmpty() || row.projects.isNotEmpty()) {
                    AssociationSummary(row)
                }
            }
        }
    }
}

/** People, then projects, each after its icon, as one text so it ellipsizes as a whole (ui.md § 3.7). */
@Composable
private fun AssociationSummary(row: NoteRowUi) {
    val separator = stringResource(R.string.names_separator)
    val text = buildAnnotatedString {
        if (row.people.isNotEmpty()) {
            appendInlineContent(PERSON_ICON)
            append(row.people.joinToString(separator))
        }
        if (row.projects.isNotEmpty()) {
            if (row.people.isNotEmpty()) appendInlineContent(GROUP_GAP)
            appendInlineContent(PROJECT_ICON)
            append(row.projects.joinToString(separator))
        }
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val icon = { id: Int ->
        InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.Center)) {
            Icon(painterResource(id), contentDescription = null, tint = tint)
        }
    }
    Text(
        text = text,
        inlineContent = mapOf(
            PERSON_ICON to icon(R.drawable.ic_person),
            PROJECT_ICON to icon(R.drawable.ic_folder),
            // Space between the two groups as layout, not text (ui.md § 11: no hard-coded text between user data).
            GROUP_GAP to InlineTextContent(Placeholder(0.75.em, 1.em, PlaceholderVerticalAlign.Center)) {},
        ),
        style = MaterialTheme.typography.labelLarge,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * What TalkBack reads for the row (ui.md § 12.1): title (or "(No data)"), body, date, "Overdue", then "People: …"
 * and "Projects: …", or "Orphaned" in their place, joined with `names_separator`.
 */
@Composable
private fun rowDescription(row: NoteRowUi, formats: DateFormats): String {
    val separator = stringResource(R.string.names_separator)
    val parts = buildList {
        add(row.title ?: stringResource(R.string.no_data))
        row.body?.let(::add)
        row.date?.let { add(formats.medium(it)) }
        if (row.overdue) add(stringResource(R.string.label_overdue))
        if (row.orphan) {
            add(stringResource(R.string.label_orphaned))
        } else {
            if (row.people.isNotEmpty()) add(stringResource(R.string.a11y_row_people, row.people.joinToString(separator)))
            if (row.projects.isNotEmpty()) {
                add(stringResource(R.string.a11y_row_projects, row.projects.joinToString(separator)))
            }
        }
    }
    return parts.joinToString(separator)
}
