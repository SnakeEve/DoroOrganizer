package com.doro.organizer.model

/**
 * The bound arguments of the single perspective query (data-layer.md § Perspective query), built by
 * [NoteFilter.toQuery]. Every non-null or true field narrows the list; all of them combine with AND (DEC-28).
 *
 * @property searchPattern the escaped LIKE term of [SearchText.toLikeTerm], or null for no search
 */
data class NoteQuery(
    val personId: Long?,
    val projectId: Long?,
    val orphanOnly: Boolean,
    val undatedOnly: Boolean,
    val fromDay: Long?,
    val toDay: Long?,
    val searchPattern: String?,
) {
    override fun toString() = "NoteQuery(personId=$personId, projectId=$projectId, orphanOnly=$orphanOnly, " +
        "undatedOnly=$undatedOnly, fromDay=$fromDay, toDay=$toDay, searchPattern=<${searchPattern?.length ?: 0} chars>)"
}
