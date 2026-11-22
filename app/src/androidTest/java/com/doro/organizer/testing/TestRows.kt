package com.doro.organizer.testing

import androidx.core.content.contentValuesOf
import androidx.sqlite.db.SupportSQLiteDatabase
import com.doro.organizer.model.SearchText
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Writes rows with bound SQL on [db], independently of the DAOs that read them, so a read test never depends on a
 * write path it does not check. Notes get `content_fold` from the production fold (DEC-29) and `preview` from the
 * production `PREVIEW_EXPR`, evaluated by SQLite ([RAW_NOTE_INSERT], INV-13), as the single write path does. Returns
 * ids through `insert` or `executeInsert`, never `last_insert_rowid()`, whose answer depends on the pooled connection.
 *
 * Writes bypass Room's transaction API, so they notify no Room `Flow`: a test that expects a re-emission runs the
 * write inside `withTransaction`, whose end refreshes the invalidation tracker.
 */
class TestRows(private val db: SupportSQLiteDatabase) {
    /** A note; it is checked exactly when [checkedAt] is set (DEC-22). */
    fun note(
        content: String = "",
        createdAt: Long,
        updatedAt: Long = createdAt,
        checkedAt: Long? = null,
        date: Long? = null,
    ): Long = db.compileStatement(RAW_NOTE_INSERT).use { insert ->
        insert.bindNull(1)
        if (date != null) insert.bindLong(2, date) else insert.bindNull(2)
        insert.bindLong(3, if (checkedAt != null) 1 else 0)
        if (checkedAt != null) insert.bindLong(4, checkedAt) else insert.bindNull(4)
        insert.bindLong(5, createdAt)
        insert.bindLong(6, updatedAt)
        insert.bindString(7, content)
        insert.bindString(8, SearchText.fold(content))
        insert.executeInsert()
    }

    fun person(name: String, createdAt: Long): Long =
        insert("persons", "name" to name, "created_at" to createdAt, "updated_at" to createdAt)

    fun project(name: String, createdAt: Long): Long =
        insert("projects", "name" to name, "created_at" to createdAt, "updated_at" to createdAt)

    fun linkPerson(noteId: Long, personId: Long) {
        insert("note_persons", "note_id" to noteId, "person_id" to personId)
    }

    fun linkProject(noteId: Long, projectId: Long) {
        insert("note_projects", "note_id" to noteId, "project_id" to projectId)
    }

    fun deletePerson(id: Long) {
        db.execSQL("DELETE FROM note_persons WHERE person_id = ?", arrayOf(id))
        db.execSQL("DELETE FROM persons WHERE id = ?", arrayOf(id))
    }

    fun deleteProject(id: Long) {
        db.execSQL("DELETE FROM note_projects WHERE project_id = ?", arrayOf(id))
        db.execSQL("DELETE FROM projects WHERE id = ?", arrayOf(id))
    }

    /** The seed database of testing.md § 7 (`seed-10k` by default), with its own ids. */
    fun seed(model: SeedModel = SeedGenerator().generate()): SeedModel = db.seed(model)

    private fun insert(table: String, vararg values: Pair<String, Any?>): Long =
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, contentValuesOf(*values))
}
