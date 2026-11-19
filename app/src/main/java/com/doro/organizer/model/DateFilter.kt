package com.doro.organizer.model

/** The date dimension of a [NoteFilter] (DEC-28). */
sealed interface DateFilter {
    /** No date condition. */
    data object Any : DateFilter

    /** Notes without a date; never offered in the Date perspective. */
    data object Undated : DateFilter

    /**
     * Epoch days, inclusive at both ends; a null end is open. Every range excludes undated notes, `Range(null, null)`
     * included ([toQuery]).
     */
    data class Range(val fromDay: Long?, val toDay: Long?) : DateFilter {
        override fun toString() = "Range(fromDay=$fromDay, toDay=$toDay)"
    }
}
