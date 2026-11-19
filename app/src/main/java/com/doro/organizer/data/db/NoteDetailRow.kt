package com.doro.organizer.data.db

import androidx.room.ColumnInfo

/** The note columns the editor loads ([NOTE_DETAIL_SQL]); the links come from separate statements. */
data class NoteDetailRow(
    val id: Long,
    val content: String,
    val date: Long?,
    val checked: Boolean,
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun toString() = "NoteDetailRow(id=$id)" // DEC-70
}
