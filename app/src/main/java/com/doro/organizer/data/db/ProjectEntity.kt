package com.doro.organizer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A row of `projects` (data-layer.md § Entities). Names are trimmed, non-blank and may repeat (DEC-32, INV-9). */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Kept in the schema, always null in v1 (DEC-31). */
    val description: String? = null,
    /** Kept in the schema, always null in v1 (DEC-31). */
    val status: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun toString() = "ProjectEntity(id=$id)" // DEC-70
}
