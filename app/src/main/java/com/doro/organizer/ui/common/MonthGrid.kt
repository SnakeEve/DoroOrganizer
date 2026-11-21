package com.doro.organizer.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** What the Date perspective has selected (DEC-53): one day, or a closed range of at least two days (epoch days). */
sealed interface DaySelection {
    data class Day(val epochDay: Long) : DaySelection {
        override fun toString() = "Day($epochDay)"
    }

    data class Range(val fromDay: Long, val toDay: Long) : DaySelection {
        override fun toString() = "Range($fromDay..$toDay)"
    }
}

/** The narrowest day column (DEC-55): below 7 of them the grid scrolls horizontally instead of shrinking. */
private val MIN_CELL = 48.dp
private val CIRCLE = 40.dp
private val GRID_PADDING = 8.dp

/**
 * The calendar of the Date perspective (ui.md § 4.4.1, DEC-11, DEC-53). It holds no state but its own pager: the
 * displayed [month], the [selection] and the [markedDays] (epoch days of [month] with matching notes) are
 * parameters, and swipes, arrows, day taps and "Today" are reported.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selection: DaySelection,
    markedDays: Set<Long>,
    firstDayOfWeek: DayOfWeek,
    onMonthChange: (YearMonth) -> Unit,
    onDayClick: (Long) -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberDateFormats()
    val pagerState = rememberPagerState(initialPage = MonthPages.pageOf(month)) { MonthPages.COUNT }
    val currentMonth by rememberUpdatedState(month)
    val currentOnMonthChange by rememberUpdatedState(onMonthChange)
    // Follows changes made from outside ("Today", "Select range", restored state) ...
    LaunchedEffect(month) {
        val page = MonthPages.pageOf(month)
        if (pagerState.settledPage != page) pagerState.animateScrollToPage(page)
    }
    // ... and reports the swipes and arrows; a page that only passes by during a swipe never settles.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect { page ->
            if (page != MonthPages.pageOf(currentMonth)) currentOnMonthChange(MonthPages.monthOf(page))
        }
    }
    Column(modifier.testTag("month_grid")) {
        MonthHeader(month, today, selection, formats, pagerState, onToday)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val padding = if (maxWidth >= MIN_CELL * MonthPages.COLUMNS + GRID_PADDING * 2) GRID_PADDING else 0.dp
            val column = maxOf(MIN_CELL, (maxWidth - padding * 2) / MonthPages.COLUMNS)
            val textHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
            val rowHeight = maxOf(MIN_CELL, textHeight + 16.dp, minOf(column, 56.dp))
            val scrolls = maxWidth < MIN_CELL * MonthPages.COLUMNS
            HorizontalPager(state = pagerState, beyondViewportPageCount = 0) { page ->
                val pageMonth = MonthPages.monthOf(page)
                MonthPage(
                    month = pageMonth,
                    today = today,
                    selection = selection,
                    // A page that is only passing by shows no marks until it settles (ui.md § 4.4.1).
                    markedDays = if (pageMonth == month) markedDays else emptySet(),
                    firstDayOfWeek = firstDayOfWeek,
                    formats = formats,
                    column = column,
                    rowHeight = rowHeight,
                    modifier = Modifier
                        .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = padding),
                    onDayClick = onDayClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthHeader(
    month: YearMonth,
    today: LocalDate,
    selection: DaySelection,
    formats: DateFormats,
    pagerState: PagerState,
    onToday: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The pager's own target, not [month]: that changes only once a page settled, so a second tap during the
    // animation would aim at the same page (ui.md § 4.4.1).
    val page = pagerState.targetPage
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formats.month(month),
            modifier = Modifier
                .weight(1f)
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            style = MaterialTheme.typography.titleMedium,
        )
        if (month != YearMonth.from(today) || selection != DaySelection.Day(today.toEpochDay())) {
            TextButton(onClick = onToday) { Text(stringResource(R.string.action_today)) }
        }
        IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } }, enabled = page > 0) {
            Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.a11y_previous_month))
        }
        IconButton(
            onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
            enabled = page < MonthPages.COUNT - 1,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.a11y_next_month))
        }
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    today: LocalDate,
    selection: DaySelection,
    markedDays: Set<Long>,
    firstDayOfWeek: DayOfWeek,
    formats: DateFormats,
    column: Dp,
    rowHeight: Dp,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val lead = MonthPages.leadingBlanks(month, firstDayOfWeek)
    Column(modifier) {
        // Every day cell announces its weekday, so the labels are hidden from accessibility.
        Row(Modifier.clearAndSetSemantics { }) {
            repeat(MonthPages.COLUMNS) { i ->
                Text(
                    text = firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                    modifier = Modifier.width(column),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(Modifier.semantics { collectionInfo = CollectionInfo(MonthPages.ROWS, MonthPages.COLUMNS) }) {
            repeat(MonthPages.ROWS) { rowIndex ->
                Row {
                    repeat(MonthPages.COLUMNS) { columnIndex ->
                        val dayOfMonth = rowIndex * MonthPages.COLUMNS + columnIndex - lead + 1
                        if (dayOfMonth !in 1..month.lengthOfMonth()) {
                            Box(Modifier.size(column, rowHeight))
                        } else {
                            val date = month.atDay(dayOfMonth)
                            DayCell(
                                date = date,
                                today = today,
                                selection = selection,
                                marked = date.toEpochDay() in markedDays,
                                formats = formats,
                                modifier = Modifier
                                    .size(column, rowHeight)
                                    .semantics { collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1) },
                                onClick = { onDayClick(date.toEpochDay()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    selection: DaySelection,
    marked: Boolean,
    formats: DateFormats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = date.toEpochDay()
    val range = selection as? DaySelection.Range
    val isStart = range?.fromDay == day
    val isEnd = range?.toDay == day
    val inBand = range != null && day > range.fromDay && day < range.toDay
    val filled = isStart || isEnd || (selection as? DaySelection.Day)?.epochDay == day
    val isToday = date == today
    val colors = MaterialTheme.colorScheme
    val description = dayDescription(date, isToday, marked, isStart, isEnd, inBand, formats)
    val clickLabel = stringResource(R.string.a11y_day_click)
    Box(
        modifier = modifier
            .testTag("day_$day")
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = filled
            },
        contentAlignment = Alignment.Center,
    ) {
        // The band joins the range's circles: full width between them, half width on the start and end cells.
        when {
            inBand -> Box(Modifier.fillMaxWidth().height(CIRCLE).background(colors.primaryContainer))
            isStart && !isEnd -> Band(Alignment.CenterEnd)
            isEnd && !isStart -> Band(Alignment.CenterStart)
        }
        Box(
            modifier = Modifier
                .size(CIRCLE)
                .clip(CircleShape)
                .then(if (filled) Modifier.background(colors.primary) else Modifier)
                .then(if (isToday) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formats.dayNumber(date),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isToday) FontWeight.Bold else null,
                color = when {
                    filled -> colors.onPrimary
                    inBand -> colors.onPrimaryContainer
                    isToday -> colors.primary
                    else -> colors.onSurface
                },
            )
            if (marked) {
                // A shape, so a mark never relies on color alone (DEC-55).
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (filled) colors.onPrimary else colors.primary),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.Band(alignment: Alignment) {
    Box(
        Modifier
            .align(alignment)
            .fillMaxWidth(0.5f)
            .height(CIRCLE)
            .background(MaterialTheme.colorScheme.primaryContainer),
    )
}

/** The full date, then "today", "has notes" and the range parts that apply (ui.md § 4.4.1). */
@Composable
private fun dayDescription(
    date: LocalDate,
    isToday: Boolean,
    marked: Boolean,
    isStart: Boolean,
    isEnd: Boolean,
    inBand: Boolean,
    formats: DateFormats,
): String {
    val parts = buildList {
        add(formats.full(date))
        if (isToday) add(stringResource(R.string.a11y_day_today))
        if (marked) add(stringResource(R.string.a11y_day_has_notes))
        if (isStart) add(stringResource(R.string.a11y_day_range_start))
        if (isEnd) add(stringResource(R.string.a11y_day_range_end))
        if (inBand) add(stringResource(R.string.a11y_day_in_range))
    }
    return parts.joinToString(stringResource(R.string.names_separator))
}
