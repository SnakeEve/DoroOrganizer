package com.doro.organizer.ui.common

import com.doro.organizer.model.DateRules
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.TextWhitespace
import java.time.LocalDate

/**
 * What a note row shows (ui.md § 3.7). [title] null means blank content, rendered as "(No data)" (DEC-25); the
 * association lists omit the perspective's primary entity and keep the row order of DEC-32.
 */
data class NoteRowUi(
    val id: Long,
    val title: String?,
    val body: String?,
    val checked: Boolean,
    val date: LocalDate?,
    val overdue: Boolean,
    val people: List<String>,
    val projects: List<String>,
    val orphan: Boolean,
) {
    override fun toString() = "NoteRowUi(id=$id)" // DEC-70
}

/**
 * The row of this note on [today] (DEC-26): the title is the first non-blank line of the preview and the body the
 * further non-blank lines, each trimmed by the app's one whitespace rule (DEC-24, DEC-39). The persons with id
 * [omitPersonId] and projects with id [omitProjectId], the primary entity of Individual or Project, are left out.
 */
fun NoteRow.toRowUi(today: LocalDate, omitPersonId: Long? = null, omitProjectId: Long? = null): NoteRowUi {
    val lines = preview.lines().map(TextWhitespace::trim).filter(String::isNotEmpty)
    return NoteRowUi(
        id = id,
        title = lines.firstOrNull(),
        body = lines.drop(1).takeIf { it.isNotEmpty() }?.joinToString("\n"),
        checked = checked,
        date = date?.let(LocalDate::ofEpochDay),
        overdue = DateRules.isOverdue(checked, date, today),
        people = persons.filter { it.id != omitPersonId }.map { it.name },
        projects = projects.filter { it.id != omitProjectId }.map { it.name },
        orphan = orphan,
    )
}
