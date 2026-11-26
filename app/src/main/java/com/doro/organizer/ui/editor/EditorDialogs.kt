package com.doro.organizer.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doro.organizer.R
import com.doro.organizer.model.DateRules
import com.doro.organizer.model.EntityKind
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.common.DestructiveButton
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.EntityDeleteDialog
import com.doro.organizer.ui.common.SecureDialogWindow

/** The dialog or sheet that [EditorUiState.dialog] names (ui.md § 6.14); each is composed by the editor. */
@Composable
internal fun EditorDialogs(state: EditorUiState, vm: EditorViewModel) {
    when (val dialog = state.dialog) {
        null -> Unit
        EditorDialog.DeleteNote -> DeleteNoteDialog(onConfirm = vm::deleteNote, onDismiss = vm::closeDialog)
        EditorDialog.Unlink -> UnlinkSheet(state, onUnlink = vm::removeLink, onDismiss = vm::closeDialog)
        EditorDialog.DatePicker -> EditorDateDialog(state, onConfirm = vm::setDate, onDismiss = vm::closeDialog)
        is EditorDialog.DeleteImpact -> EntityDeleteDialog(
            kind = dialog.kind,
            name = dialog.name,
            n = dialog.n,
            m = dialog.m,
            onConfirm = { vm.deleteEntity(dialog.kind, dialog.id) },
            onDismiss = vm::closeDialog,
        )
    }
}

/** "Delete this note?" (ui.md § 8.2, DEC-35); "Delete" is destructive. */
@Composable
private fun DeleteNoteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_note_title)) },
        text = { SecureDialogWindow { DialogContent { Text(stringResource(R.string.dialog_irreversible)) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { DestructiveButton(onConfirm) },
    )
}

/**
 * The date picker of the date field (ui.md § 6.5): the draft's date, else today; OK, enabled while a day is selected,
 * sets the draft date. Picker millis convert with UTC (DEC-26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDateDialog(state: EditorUiState, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val today by LocalUiGraph.current.appTime.todayFlow.collectAsStateWithLifecycle()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = state.date?.let { DateRules.pickerMillisOf(it.toEpochDay()) },
        initialDisplayedMonthMillis = DateRules.pickerMillisOf((state.date ?: today).toEpochDay()),
        yearRange = DatePickerDefaults.YearRange,
    )
    val selected = pickerState.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { selected?.let { onConfirm(DateRules.epochDayOfPickerMillis(it)) } }, enabled = selected != null) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        SecureDialogWindow { DatePicker(state = pickerState, showModeToggle = true) }
    }
}

/**
 * The unlink sheet (ui.md § 6.10): every chip of the association area in two sections; "Unlink" removes that link
 * from the draft at once, the person or project itself stays. With no link left it says the note moves to Orphans
 * (§3, DEC-27) and offers "Done".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlinkSheet(state: EditorUiState, onUnlink: (EntityKind, LinkChip) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SecureDialogWindow {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.unlink_title),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                UnlinkSection(R.string.assoc_people, state.people) { onUnlink(EntityKind.Person, it) }
                UnlinkSection(R.string.assoc_projects, state.projects) { onUnlink(EntityKind.Project, it) }
                if (state.people.isEmpty() && state.projects.isEmpty()) {
                    Text(
                        text = stringResource(R.string.unlink_now_orphan),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }
}

/** One kind's links under its heading; omitted when the kind has none. */
@Composable
private fun UnlinkSection(label: Int, chips: List<LinkChip>, onUnlink: (LinkChip) -> Unit) {
    if (chips.isEmpty()) return
    Text(
        text = stringResource(label),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    chips.forEach { chip ->
        val description = stringResource(R.string.a11y_unlink_entity, chip.name)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chip.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { onUnlink(chip) }, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(R.string.action_unlink))
            }
        }
    }
}
