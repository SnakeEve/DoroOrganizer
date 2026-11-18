package com.doro.organizer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A link of `note_projects` (data-layer.md § Entities). `note_id` leads the primary key, which covers its foreign
 * key, so §9's `idx_note_projects_note` is not created (DEC-30). `CASCADE` is a safety net only: code deletes
 * junction rows explicitly.
 */
@Entity(
    tableName = "note_projects",
    primaryKeys = ["note_id", "project_id"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["note_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProjectEntity::class, ["id"], ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("project_id", name = "idx_note_projects_project")],
)
data class NoteProjectCrossRef(
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "project_id") val projectId: Long,
) {
    override fun toString() = "NoteProjectCrossRef(noteId=$noteId, projectId=$projectId)" // DEC-70
}
