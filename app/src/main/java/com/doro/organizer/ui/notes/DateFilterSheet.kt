package com.doro.organizer.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.DateRules
import com.doro.organizer.ui.common.DateFormats
import com.doro.organizer.ui.common.SecureDialogWindow
import java.time.LocalDate

/**
 * The date filter sheet (ui.md § 3.5): "Any date", "Undated" (when [offersUndated]) and "Date range" with its two
 * ends. Every change applies at once; the list behind the sheet follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateFilterSheet(
    filter: DateFilter,
    offersUndated: Boolean,
    formats: DateFormats,
    onSelect: (DateFilter) -> Unit,
    onSelectRange: () -> Unit,
    onEditEnd: (RangeEnd) -> Unit,
    onClearEnd: (RangeEnd) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SecureDialogWindow {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.date_filter_title),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(Modifier.selectableGroup()) {
                    RadioRow(R.string.date_filter_any, selected = filter == DateFilter.Any) {
                        onSelect(DateFilter.Any)
                    }
                    if (offersUndated) {
                        RadioRow(R.string.filter_date_undated, selected = filter == DateFilter.Undated) {
                            onSelect(DateFilter.Undated)
                        }
                    }
                    RadioRow(
                        R.string.date_filter_range,
                        selected = filter is DateFilter.Range,
                        onClick = onSelectRange,
                    )
                }
                val range = filter as? DateFilter.Range
                EndRow(RangeEnd.From, range?.fromDay, formats, onEditEnd, onClearEnd)
                EndRow(RangeEnd.Until, range?.toDay, formats, onEditEnd, onClearEnd)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

/** A radio row whose whole area is the target, read as one node (ui.md § 3.5). */
@Composable
private fun RadioRow(label: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** "From" or "Until", indented under "Date range": the end's date or "Any", with a clear button while set. */
@Composable
private fun EndRow(
    end: RangeEnd,
    epochDay: Long?,
    formats: DateFormats,
    onEdit: (RangeEnd) -> Unit,
    onClear: (RangeEnd) -> Unit,
) {
    val label = if (end == RangeEnd.From) R.string.date_filter_from else R.string.date_filter_until
    val clearLabel = if (end == RangeEnd.From) R.string.action_clear_from else R.string.action_clear_until
    val value = epochDay?.let(formats::medium) ?: stringResource(R.string.filter_any)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { onEdit(end) }
                .semantics { stateDescription = value }
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (epochDay != null) {
            IconButton(onClick = { onClear(end) }) { Icon(painterResource(R.drawable.ic_close), stringResource(clearLabel)) }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * The picker of one range end (ui.md § 3.5 rules 3, 6, 8): it shows that end's date, else [today]; days beyond the
 * other end are disabled, so a reversed range cannot be entered. Picker millis convert with UTC (DEC-26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateEndPickerDialog(
    end: RangeEnd,
    filter: DateFilter,
    today: LocalDate,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val range = filter as? DateFilter.Range
    val current = if (end == RangeEnd.From) range?.fromDay else range?.toDay
    val otherEnd = if (end == RangeEnd.From) range?.toDay else range?.fromDay
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current?.let(DateRules::pickerMillisOf),
        initialDisplayedMonthMillis = DateRules.pickerMillisOf(current ?: today.toEpochDay()),
        yearRange = DatePickerDefaults.YearRange,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = DateRules.epochDayOfPickerMillis(utcTimeMillis)
                return when {
                    otherEnd == null -> true
                    end == RangeEnd.From -> day <= otherEnd
                    else -> day >= otherEnd
                }
            }
        },
    )
    val selected = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onConfirm(DateRules.epochDayOfPickerMillis(it)) } },
                enabled = selected != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        SecureDialogWindow { DatePicker(state = state, showModeToggle = true) }
    }
}
