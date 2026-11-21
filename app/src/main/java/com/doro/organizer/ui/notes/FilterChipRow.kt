package com.doro.organizer.ui.notes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.DateFilter
import com.doro.organizer.ui.common.DateFormats

/**
 * The filter chips below the app bar (ui.md § 3.4), one per entry of [chips], scrolling horizontally. [onChipClick]
 * receives the dimension; without [enabled] (selection mode, ui.md § 5.1) the chips are disabled.
 */
@Composable
internal fun FilterChipRow(
    chips: List<FilterChipUi>,
    formats: DateFormats,
    enabled: Boolean,
    onChipClick: (FilterDimension) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            FilterChipItem(chip, formats, enabled) { onChipClick(chip.dimension) }
        }
    }
}

@Composable
private fun FilterChipItem(chip: FilterChipUi, formats: DateFormats, enabled: Boolean, onClick: () -> Unit) {
    val value = chipValueText(chip.value, formats)
    val label = value ?: stringResource(chip.dimension.nameRes())
    val description = stringResource(chip.dimension.a11yRes(), value ?: stringResource(R.string.filter_any))
    val changeLabel = stringResource(R.string.a11y_change_filter)
    FilterChip(
        selected = value != null,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier
            .testTag("chip_${chip.dimension.name}")
            .clearAndSetSemantics {
                role = Role.DropdownList
                contentDescription = description
                if (enabled) {
                    onClick(label = changeLabel) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        leadingIcon = if (value != null) {
            { ChipIcon(chip.dimension.iconRes()) }
        } else {
            null
        },
        trailingIcon = { ChipIcon(R.drawable.ic_arrow_drop_down) },
    )
}

/** A decorative chip icon: the chip's semantics carry the meaning. */
@Composable
private fun ChipIcon(@DrawableRes id: Int) {
    Icon(painterResource(id), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}

/** The value a set chip shows in place of its dimension's name; null while not set. */
@Composable
private fun chipValueText(value: ChipValue, formats: DateFormats): String? = when (value) {
    ChipValue.NotSet -> null
    is ChipValue.Entity -> value.name
    is ChipValue.Date -> dateFilterText(value.filter, formats)
}

/** "Undated", "17 Nov 2026 – 23 Nov 2026", "From 17 Nov 2026" or "Until 23 Nov 2026"; null for "Any date". */
@Composable
internal fun dateFilterText(filter: DateFilter, formats: DateFormats): String? = when (filter) {
    DateFilter.Any -> null
    DateFilter.Undated -> stringResource(R.string.filter_date_undated)
    is DateFilter.Range -> {
        val from = filter.fromDay?.let(formats::medium)
        val until = filter.toDay?.let(formats::medium)
        when {
            from != null && until != null -> stringResource(R.string.date_range_both, from, until)
            from != null -> stringResource(R.string.date_range_from, from)
            until != null -> stringResource(R.string.date_range_until, until)
            else -> null
        }
    }
}

private fun FilterDimension.nameRes(): Int = when (this) {
    FilterDimension.Person -> R.string.filter_person
    FilterDimension.Project -> R.string.filter_project
    FilterDimension.Date -> R.string.filter_date
}

private fun FilterDimension.a11yRes(): Int = when (this) {
    FilterDimension.Person -> R.string.a11y_filter_person
    FilterDimension.Project -> R.string.a11y_filter_project
    FilterDimension.Date -> R.string.a11y_filter_date
}

@DrawableRes
private fun FilterDimension.iconRes(): Int = when (this) {
    FilterDimension.Person -> R.drawable.ic_person
    FilterDimension.Project -> R.drawable.ic_folder
    FilterDimension.Date -> R.drawable.ic_event
}
