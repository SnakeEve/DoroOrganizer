package com.doro.organizer.data

import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.model.EntityOption

/**
 * Maps one emission of a picker query into the repository order (data-layer.md § Editor loads, pickers and existence
 * probes): co-linked entities first (DEC-28), then [nameOrder] (DEC-32). Runs on the background dispatcher.
 */
internal fun List<EntityOptionRow>.toEntityOptions(): List<EntityOption> {
    val order = compareBy<EntityOptionRow> { !it.coLinked }.then(nameOrder({ it.name }, { it.id }))
    return sortedWith(order).map { row ->
        EntityOption(
            id = row.id,
            name = row.name,
            createdAt = row.createdAt,
            noteCount = row.noteCount,
            coLinked = row.coLinked,
        )
    }
}
