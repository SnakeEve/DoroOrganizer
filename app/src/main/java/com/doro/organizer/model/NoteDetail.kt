package com.doro.organizer.model

/** The editor's load result (data-layer.md § Editor loads); [persons] and [projects] are sorted like row links. */
data class NoteDetail(
    val id: Long,
    val content: String,
    val date: Long?,
    val checked: Boolean,
    val checkedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val persons: List<EntityRef>,
    val projects: List<EntityRef>,
) {
    override fun toString() = "NoteDetail(id=$id)" // DEC-70
}
