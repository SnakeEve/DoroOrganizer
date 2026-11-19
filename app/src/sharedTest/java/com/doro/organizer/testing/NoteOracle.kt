package com.doro.organizer.testing

import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.TextWhitespace
import java.util.Locale

/**
 * An independent Kotlin reference for the perspective query (testing.md I-14): the filter semantics of DEC-28 and
 * the perspective table of data-layer.md, the fold of DEC-29 and the order of DEC-23, written without the SQL, the
 * query mapping or the search escaping it checks.
 */
object NoteOracle {
    /** DEC-23: unchecked first, newest update first; then checked, oldest check first; ids break every tie. */
    val canonicalOrder: Comparator<SeedNote> = Comparator { a, b ->
        when {
            a.checked != b.checked -> if (a.checked) 1 else -1
            !a.checked -> compareValuesBy(b, a, { it.updatedAt }, { it.createdAt }, { it.id })
            else -> compareValues(a.checkedAt, b.checkedAt).takeIf { it != 0 } ?: compareValues(b.id, a.id)
        }
    }

    /** The ids of the list that [filter] shows, in canonical order. */
    fun listIds(model: SeedModel, filter: NoteFilter): List<Long> =
        model.notes.filter { matches(it, filter) }.sortedWith(canonicalOrder).map { it.id }

    /**
     * The days of [fromDay]..[toDay] holding a note that matches the person, project and search filters of the
     * Date perspective's [filter] (DEC-53); checked notes count.
     */
    fun markedDays(model: SeedModel, filter: NoteFilter, fromDay: Long, toDay: Long): Set<Long> {
        require(filter.perspective == Perspective.Date) { "marks belong to the Date perspective" }
        val everyDay = filter.copy(primaryDays = DateFilter.Range(fromDay, toDay))
        return model.notes.filter { matches(it, everyDay) }.mapNotNull { it.date }.toSet()
    }

    private fun matches(note: SeedNote, filter: NoteFilter): Boolean {
        val person = when (filter.perspective) {
            Perspective.Individual -> filter.primaryId
            Perspective.Orphan -> null
            else -> filter.personId
        }
        val project = when (filter.perspective) {
            Perspective.Project -> filter.primaryId
            Perspective.Orphan -> null
            else -> filter.projectId
        }
        val date = if (filter.perspective == Perspective.Date) filter.primaryDays!! else filter.date
        val orphan = note.personIds.isEmpty() && note.projectIds.isEmpty()
        return (person == null || person in note.personIds) &&
            (project == null || project in note.projectIds) &&
            (filter.perspective != Perspective.Orphan || orphan) &&
            dateMatches(note.date, date) &&
            searchMatches(note, filter.search)
    }

    private fun dateMatches(date: Long?, filter: DateFilter): Boolean = when (filter) {
        DateFilter.Any -> true
        DateFilter.Undated -> date == null
        is DateFilter.Range -> {
            val ends = listOfNotNull(filter.fromDay, filter.toDay)
            val low = if (ends.size == 2) ends.min() else filter.fromDay
            val high = if (ends.size == 2) ends.max() else filter.toDay
            date != null && (low == null || date >= low) && (high == null || date <= high)
        }
    }

    private fun searchMatches(note: SeedNote, search: String): Boolean {
        val term = TextWhitespace.trim(search).lowercase(Locale.ROOT)
        return term.isEmpty() || note.foldedContent.contains(term)
    }
}
