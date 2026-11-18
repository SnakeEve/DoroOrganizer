package com.doro.organizer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A link of `note_persons` (data-layer.md § Entities). `note_id` leads the primary key, which covers its foreign
 * key, so §9's `idx_note_persons_note` is not created (DEC-30). `CASCADE` is a safety net only: code deletes
 * junction rows explicitly.
 */
@Entity(
    tableName = "note_persons",
    primaryKeys = ["note_id", "person_id"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["note_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PersonEntity::class, ["id"], ["person_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("person_id", name = "idx_note_persons_person")],
)
data class NotePersonCrossRef(
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
) {
    override fun toString() = "NotePersonCrossRef(noteId=$noteId, personId=$personId)" // DEC-70
}
