package com.doro.organizer.model

/**
 * One row of a note list (data-layer.md § Rows). A list never loads the full content (DEC-24).
 *
 * @property preview up to 300 code points from the first non-whitespace character; blank exactly when the content
 *   is blank, and the UI then shows "(No data)" (DEC-25)
 * @property date the epoch day (DEC-26); Overdue is derived by the UI
 * @property persons the linked persons, by `Collator` on the name, then id (DEC-32)
 * @property projects the linked projects, in the same order
 */
data class NoteRow(
    val id: Long,
    val preview: String,
    val date: Long?,
    val checked: Boolean,
    val checkedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val persons: List<EntityRef>,
    val projects: List<EntityRef>,
) {
    /** No person and no project link: derived, never stored (DEC-27). */
    val orphan: Boolean get() = persons.isEmpty() && projects.isEmpty()

    override fun toString() = "NoteRow(id=$id)"
}
