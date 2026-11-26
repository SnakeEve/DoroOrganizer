package com.doro.organizer.ui.notes

import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.model.DateFilter
import com.doro.organizer.ui.common.DaySelection
import com.doro.organizer.ui.common.NoteRowUi
import java.time.LocalDate
import java.time.YearMonth

/**
 * The one state of a list screen (ui.md § 3.10). [loading] is true until the first emission of the query of the
 * current [primary], so "(No data)" never flashes (DEC-25, architecture.md § Layering rule 2). [today] starts the
 * date pickers; [dateFilter] is the date chip's value, which the date filter sheet shows. The search text is not
 * here: the field reads `NoteListViewModel.searchText`, which changes in the frame of each edit (ui.md § 3.3).
 * [selection] is null outside selection mode (ui.md § 5); [batchRunning] is true while a batch action of the
 * contextual bar runs, whose actions are then disabled (ui.md § 5.3). [rowsRead] is the read that produced [rows]: 0,
 * then the number of each re-read after a note was created; [newNoteMarked] says that `NewNoteMarker` holds a mark,
 * which the list that is the top entry takes (ui.md § 3.11, DEC-110).
 */
data class NoteListUiState(
    val spec: PerspectiveSpec,
    val loading: Boolean,
    val primary: Primary,
    val rows: List<NoteRowUi>,
    val rowsRead: Int,
    val newNoteMarked: Boolean,
    val today: LocalDate,
    val chips: List<FilterChipUi>,
    val filtersActive: Boolean,
    val searchOpen: Boolean,
    val dateFilter: DateFilter,
    val selection: Set<Long>?,
    val batchRunning: Boolean,
    val calendar: CalendarUi?,
    val dialog: ListDialog?,
    val message: UserMessage?,
) {
    override fun toString() = "NoteListUiState(${spec.perspective}, rows=${rows.size})" // DEC-70

    /** Selection mode is on (ui.md § 5.1). */
    val selecting: Boolean get() = selection != null
}

/** The primary selection of a perspective (ui.md § 4): what the list is of, before any secondary filter. */
sealed interface Primary {
    /** Individual and Project until the settings and the existence probe have answered (ui.md § 4.2). */
    data object Loading : Primary

    /** All and Orphan; Individual and Project with nothing chosen, which show the entity picker (DEC-52). */
    data object None : Primary

    /** The chosen person or project of Individual or Project. */
    data class Entity(val id: Long, val name: String) : Primary {
        override fun toString() = "Entity(id=$id)" // DEC-70
    }

    /** The selected day of Date (DEC-53). */
    data class Day(val epochDay: Long) : Primary {
        override fun toString() = "Day($epochDay)"
    }

    /** The selected range of Date, inclusive, at least two days. */
    data class Range(val fromDay: Long, val toDay: Long) : Primary {
        override fun toString() = "Range($fromDay..$toDay)"
    }
}

/** One filter chip (ui.md § 3.4). */
data class FilterChipUi(val dimension: FilterDimension, val value: ChipValue) {
    override fun toString() = "FilterChipUi($dimension, $value)" // DEC-70: ChipValue redacts names
}

/** What a chip shows: its dimension's name while not set, else the set value. */
sealed interface ChipValue {
    data object NotSet : ChipValue

    data class Entity(val id: Long, val name: String) : ChipValue {
        override fun toString() = "Entity(id=$id)" // DEC-70
    }

    /** `Undated` or a `Range` with at least one end (ui.md § 3.5). */
    data class Date(val filter: DateFilter) : ChipValue {
        override fun toString() = "Date($filter)"
    }
}

/** The calendar of Date (ui.md § 4.4): the displayed month, today's marker, the selection and the marked days. */
data class CalendarUi(
    val month: YearMonth,
    val today: LocalDate,
    val selection: DaySelection,
    val markedDays: Set<Long>,
) {
    override fun toString() = "CalendarUi($month, $selection, marks=${markedDays.size})"
}

/** An end of the date filter's range (ui.md § 3.5). */
enum class RangeEnd { From, Until }

/** The open sheet or dialog of a list screen; saved, so each survives rotation and process death (ui.md § 3.10). */
sealed interface ListDialog {
    /** The date filter sheet (ui.md § 3.5). */
    data object DateFilterSheet : ListDialog

    /** The From or Until date picker over the date filter sheet (ui.md § 3.5). */
    data class DateEndPicker(val end: RangeEnd) : ListDialog {
        override fun toString() = "DateEndPicker($end)"
    }

    /** The Calendar's range picker (ui.md § 4.4.2). */
    data object RangePicker : ListDialog

    /** The batch date picker of selection mode (ui.md § 5.3). */
    data object SetDate : ListDialog

    /** The batch delete confirmation of selection mode (ui.md § 8.3). */
    data object BatchDelete : ListDialog

    /** "Rename…" of the overflow menu of Individual and Project, for the entity [id] the tab shows (ui.md § 8.17). */
    data class Rename(val id: Long) : ListDialog {
        override fun toString() = "Rename(id=$id)"
    }

    /**
     * "Delete…" of the overflow menu of Individual and Project (ui.md § 8.4, DEC-113): the impact of deleting the entity
     * [id] the tab shows, [n] linked notes, [m] of them would become orphans.
     */
    data class DeleteEntity(val id: Long, val n: Int, val m: Int) : ListDialog {
        override fun toString() = "DeleteEntity(id=$id, n=$n, m=$m)"
    }
}
