package com.doro.organizer.data.db

import androidx.room.ColumnInfo

/**
 * A row of [ROWS_SQL], before the link ids are resolved to names and sorted (data-layer.md § Rows). A data class, so
 * an equal emission is recognised by value and not mapped again (data-layer.md § Reactivity).
 *
 * @property personIds the linked person ids as `group_concat` writes them (`"3,17"`), in no defined order; null
 *   without person links (DEC-106)
 * @property projectIds the same for the linked projects
 */
data class NoteRowRaw(
    val id: Long,
    val preview: String,
    val date: Long?,
    val checked: Boolean,
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "person_ids") val personIds: String?,
    @ColumnInfo(name = "project_ids") val projectIds: String?,
) {
    override fun toString() = "NoteRowRaw(id=$id)" // DEC-70
}
