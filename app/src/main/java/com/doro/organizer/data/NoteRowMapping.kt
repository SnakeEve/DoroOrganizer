package com.doro.organizer.data

import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteRow
import java.text.Collator
import org.json.JSONArray
import org.json.JSONException

/**
 * Maps one emission of `NoteDao.observeRows` (data-layer.md § Rows): parses the link arrays and sorts each by
 * `Collator` on the name, then id, the picker order of DEC-32. SQLite cannot sort that way (no ICU, DEC-08). One
 * `Collator` per pass: instances are not thread-safe, and the default locale is read again on each pass. Runs on the
 * background dispatcher (architecture.md § Threading).
 */
internal fun List<NoteRowRaw>.toNoteRows(): List<NoteRow> {
    val collator = Collator.getInstance()
    val order = Comparator<EntityRef> { a, b -> collator.compare(a.name, b.name) }.thenBy { it.id }
    return map { raw ->
        NoteRow(
            id = raw.id,
            preview = raw.preview,
            date = raw.date,
            checked = raw.checked,
            checkedAt = raw.checkedAt,
            createdAt = raw.createdAt,
            updatedAt = raw.updatedAt,
            persons = parseLinks(raw.personsJson, raw.id, "persons_json").sortedWith(order),
            projects = parseLinks(raw.projectsJson, raw.id, "projects_json").sortedWith(order),
        )
    }
}

/** `json_group_array` over no rows; most rows have no links of one kind, so they skip the parser. */
private const val NO_LINKS = "[]"

private fun parseLinks(json: String, noteId: Long, column: String): List<EntityRef> {
    if (json == NO_LINKS) return emptyList()
    return try {
        val links = JSONArray(json)
        List(links.length()) { i ->
            val link = links.getJSONArray(i)
            EntityRef(id = link.getLong(0), name = link.getString(1))
        }
    } catch (e: JSONException) {
        // JSONException messages quote the input, which holds names: constant text, the id and the class name
        // only, and no cause (DEC-70, security.md § 10).
        throw IllegalStateException("$column of note $noteId is malformed (${e.javaClass.simpleName})")
    }
}
