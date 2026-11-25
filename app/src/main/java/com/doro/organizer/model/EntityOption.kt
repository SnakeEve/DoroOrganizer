package com.doro.organizer.model

/**
 * A picker row (data-layer.md § Editor loads, pickers and existence probes): [noteCount] and [createdAt] feed
 * "N notes · added <date>" (DEC-32); [coLinked] marks an entity linked to a note of the entities a picker asked about
 * (DEC-28, DEC-111).
 */
data class EntityOption(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val noteCount: Int,
    val coLinked: Boolean,
) {
    override fun toString() = "EntityOption(id=$id)" // DEC-70
}
