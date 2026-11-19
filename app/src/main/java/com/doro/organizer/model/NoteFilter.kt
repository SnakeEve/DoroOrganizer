package com.doro.organizer.model

/**
 * What a perspective shows (DEC-28): its primary selection plus the secondary filters and the search text. Which
 * dimensions a perspective keeps is decided by [toQuery].
 *
 * @property primaryId the person of Individual or the project of Project; null until one is picked (DEC-52)
 * @property primaryDays the selected day (`fromDay == toDay`) or range of Date, both ends set (DEC-53)
 */
data class NoteFilter(
    val perspective: Perspective,
    val primaryId: Long? = null,
    val primaryDays: DateFilter.Range? = null,
    val personId: Long? = null,
    val projectId: Long? = null,
    val date: DateFilter = DateFilter.Any,
    val search: String = "",
) {
    override fun toString() = "NoteFilter(perspective=$perspective, primaryId=$primaryId, primaryDays=$primaryDays, " +
        "personId=$personId, projectId=$projectId, date=$date, search=<${search.length} chars>)"
}

/**
 * The query arguments of this filter, per the table of data-layer.md § Perspective to NoteQuery mapping. Pure:
 * equal filters give equal queries. Null when Individual or Project has no [NoteFilter.primaryId] yet: the screen
 * shows the entity picker and runs no list query (DEC-52). Dimensions a perspective does not offer are dropped,
 * never combined (§2, DEC-28).
 *
 * @throws IllegalArgumentException for Date without [NoteFilter.primaryDays] or with an open end
 */
fun NoteFilter.toQuery(): NoteQuery? {
    val pattern = SearchText.toLikeTerm(search)
    return when (perspective) {
        Perspective.All -> query(personId, projectId, date, pattern)
        Perspective.Individual -> primaryId?.let { query(personId = it, projectId, date, pattern) }
        Perspective.Project -> primaryId?.let { query(personId, projectId = it, date, pattern) }
        Perspective.Date -> {
            val days = requireNotNull(primaryDays) { "the Date perspective always has a selected day or range" }
            require(days.fromDay != null && days.toDay != null) { "the Date perspective's days have both ends" }
            query(personId, projectId, days, pattern)
        }
        Perspective.Orphan -> query(personId = null, projectId = null, date, pattern).copy(orphanOnly = true)
    }
}

private fun query(personId: Long?, projectId: Long?, date: DateFilter, pattern: String?): NoteQuery {
    val bounds = (date as? DateFilter.Range)?.bounds()
    return NoteQuery(
        personId = personId,
        projectId = projectId,
        orphanOnly = false,
        undatedOnly = date == DateFilter.Undated,
        fromDay = bounds?.first,
        toDay = bounds?.second,
        searchPattern = pattern,
    )
}

/**
 * Open ends become the [EpochDays] bounds, so even `Range(null, null)` excludes undated notes (DEC-28); a reversed
 * range, which no picker produces, is swapped.
 */
private fun DateFilter.Range.bounds(): Pair<Long, Long> {
    val from = fromDay ?: EpochDays.MIN
    val to = toDay ?: EpochDays.MAX
    return if (from <= to) from to to else to to from
}
