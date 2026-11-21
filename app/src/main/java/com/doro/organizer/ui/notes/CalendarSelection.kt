package com.doro.organizer.ui.notes

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.DateRules
import com.doro.organizer.ui.common.DateFormats
import com.doro.organizer.ui.common.DaySelection
import com.doro.organizer.ui.common.SecureDialogWindow
import java.time.LocalDate

/**
 * The heading row below `MonthGrid` that labels the list (ui.md § 4.4.2): the selected day in full, marked "Today"
 * when it is today, or the range; "Select range" and, in range mode, "Clear range", both disabled without [enabled]
 * (selection mode, ui.md § 5.1).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarSelectionHeader(
    selection: DaySelection,
    today: LocalDate,
    formats: DateFormats,
    onSelectRange: () -> Unit,
    onClearRange: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when (selection) {
            is DaySelection.Day -> formats.full(LocalDate.ofEpochDay(selection.epochDay))
            is DaySelection.Range ->
                stringResource(R.string.date_range_both, formats.medium(selection.fromDay), formats.medium(selection.toDay))
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        if (selection == DaySelection.Day(today.toEpochDay())) {
            Text(
                text = stringResource(R.string.label_today),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onSelectRange, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_date_range), null, Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.action_select_range), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        if (selection is DaySelection.Range) {
            IconButton(onClick = onClearRange, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear_range))
            }
        }
    }
}

/**
 * "Select range" (ui.md § 4.4.2, DEC-53): a `DateRangePicker` that starts on the current day (no end) or on the
 * current range; OK needs both ends. Picker millis convert with UTC (DEC-26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangePickerDialog(
    selection: DaySelection,
    onConfirm: (fromDay: Long, toDay: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val (start, end) = when (selection) {
        is DaySelection.Day -> selection.epochDay to null
        is DaySelection.Range -> selection.fromDay to selection.toDay
    }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = DateRules.pickerMillisOf(start),
        initialSelectedEndDateMillis = end?.let(DateRules::pickerMillisOf),
        initialDisplayedMonthMillis = DateRules.pickerMillisOf(start),
        yearRange = DatePickerDefaults.YearRange,
    )
    val from = state.selectedStartDateMillis
    val to = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (from != null && to != null) {
                        onConfirm(DateRules.epochDayOfPickerMillis(from), DateRules.epochDayOfPickerMillis(to))
                    }
                },
                enabled = from != null && to != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        SecureDialogWindow { DateRangePicker(state = state, modifier = Modifier.weight(1f)) }
    }
}
