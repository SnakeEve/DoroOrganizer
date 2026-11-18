package com.doro.organizer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A row of `notes` (data-layer.md § Entities). Each §9 index that DEC-30 keeps has its explicit name. `content` and
 * `content_fold` are declared last, so Room emits them as the last columns and the small columns that `WHERE` and
 * `ORDER BY` read stay before any overflow page (testing.md I-47).
 */
@Entity(
    tableName = "notes",
    indices = [
        Index("date", name = "idx_notes_date"),
        Index("created_at", name = "idx_notes_created"),
        Index("updated_at", name = "idx_notes_updated"),
        Index("checked_at", name = "idx_notes_checked_at"),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, // AUTOINCREMENT: ids are never reused (DEC-30, INV-11)
    /** Epoch day of the picked calendar day, or null (DEC-26, INV-5). */
    val date: Long?,
    @ColumnInfo(defaultValue = "0") val checked: Boolean,
    /** Set exactly when [checked] is true (DEC-22, INV-3). */
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Never null; empty is `""` (DEC-24, INV-1). */
    val content: String,
    /** `SearchText.fold(content)`, written together with [content] (DEC-29, INV-2). */
    @ColumnInfo(name = "content_fold") val contentFold: String,
) {
    override fun toString() = "NoteEntity(id=$id)" // DEC-70
}
