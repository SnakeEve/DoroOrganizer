package com.doro.organizer.data

import com.doro.organizer.data.db.EntityDao
import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.model.EntityOption
import kotlinx.coroutines.flow.Flow

/**
 * The picker statement for [coLinkedTo] and [exceptNoteId] (data-layer.md § Editor loads, pickers and existence
 * probes, DEC-111): the distinct ids, at most the `SQL_CHUNK` smallest, so it binds at most 901 host parameters; a
 * read is one observed statement and cannot be chunked like a write. With no id it reads `observeOptions()`, because
 * an empty `IN ()` plans a scan of a junction table.
 */
internal fun EntityDao.observeOptionRows(coLinkedTo: List<Long>, exceptNoteId: Long?): Flow<List<EntityOptionRow>> {
    val ids = coLinkedTo.toSortedSet().take(SQL_CHUNK)
    return if (ids.isEmpty()) observeOptions() else observeCoLinkedOptions(ids, exceptNoteId)
}

/**
 * Maps one emission of a picker query into the repository order (data-layer.md § Editor loads, pickers and existence
 * probes): by [nameOrder] (DEC-32) only; the `coLinked` flag moves nothing, since the picker forms its groups itself
 * (DEC-111). Runs on the background dispatcher.
 */
internal fun List<EntityOptionRow>.toEntityOptions(): List<EntityOption> =
    sortedWith(nameOrder({ it.name }, { it.id })).map { row ->
        EntityOption(
            id = row.id,
            name = row.name,
            createdAt = row.createdAt,
            noteCount = row.noteCount,
            coLinked = row.coLinked,
        )
    }
