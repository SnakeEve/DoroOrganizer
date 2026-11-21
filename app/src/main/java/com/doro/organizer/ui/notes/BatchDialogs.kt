package com.doro.organizer.ui.notes

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.DateRules
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.NoteRowUi
import com.doro.organizer.ui.common.SecureDialogWindow
import java.time.LocalDate

/** The date every selected row shares (ui.md § 5.3); null when they differ or none has a date. */
internal fun sharedDate(rows: List<NoteRowUi>, selection: Set<Long>): LocalDate? =
    rows.asSequence().filter { it.id in selection }.map { it.date }.distinct().take(2).singleOrNull()

/**
 * "Set date" of selection mode (ui.md § 5.3): a `DatePickerDialog` that starts on [shared], else with no day selected
 * on the month of [today]. OK sets the picked day and "Remove date" removes the date, both through [onSet]; picker
 * millis convert with UTC (DEC-26). The buttons are disabled while the action runs (ui.md § 8.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetDateDialog(
    shared: LocalDate?,
    today: LocalDate,
    running: Boolean,
    onSet: (epochDay: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = shared?.let { DateRules.pickerMillisOf(it.toEpochDay()) },
        initialDisplayedMonthMillis = DateRules.pickerMillisOf((shared ?: today).toEpochDay()),
        yearRange = DatePickerDefaults.YearRange,
    )
    val selected = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onSet(DateRules.epochDayOfPickerMillis(it)) } },
                enabled = selected != null && !running,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSet(null) }, enabled = !running) {
                    Text(stringResource(R.string.action_remove_date))
                }
                TextButton(onClick = onDismiss, enabled = !running) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    ) {
        SecureDialogWindow {
            DatePicker(
                state = state,
                title = {
                    // The padding of the picker's default title, which this one replaces.
                    Text(
                        text = stringResource(R.string.dialog_set_date_title),
                        modifier = Modifier.padding(PaddingValues(start = 24.dp, end = 12.dp, top = 16.dp)),
                    )
                },
                showModeToggle = true,
            )
        }
    }
}

/**
 * The batch delete confirmation (ui.md § 8.3, DEC-35): the title counts the [count] selected notes; "Delete" is
 * destructive. The buttons are disabled while the delete runs (ui.md § 8.1).
 */
@Composable
internal fun BatchDeleteDialog(count: Int, running: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.dialog_batch_delete_title, count, count)) },
        text = { SecureDialogWindow { DialogContent { Text(stringResource(R.string.dialog_irreversible)) } } },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !running,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
    )
}
