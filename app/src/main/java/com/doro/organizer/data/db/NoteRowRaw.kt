package com.doro.organizer.data.db

import androidx.room.ColumnInfo

/**
 * A row of [ROWS_SQL], before the links are parsed and sorted (data-layer.md § Rows). A data class, so an equal
 * emission is recognised by value and neither parsed nor sorted again (data-layer.md § Reactivity).
 *
 * @property personsJson `[[id, name], …]` of the linked persons, `[]` without links
 * @property projectsJson the same for the linked projects
 */
data class NoteRowRaw(
    val id: Long,
    val preview: String,
    val date: Long?,
    val checked: Boolean,
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "persons_json") val personsJson: String,
    @ColumnInfo(name = "projects_json") val projectsJson: String,
) {
    override fun toString() = "NoteRowRaw(id=$id)" // DEC-70
}
