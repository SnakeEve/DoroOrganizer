package com.doro.organizer.testing

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.SearchText

/**
 * The seed database writer of testing.md § 7: inserts [model] with its own ids in one bulk transaction, computing
 * `content_fold` with the production fold (DEC-29) and `preview` with the production `PREVIEW_EXPR` in SQLite
 * ([RAW_NOTE_INSERT], INV-13), then asserts the database invariants (I-01).
 */
fun AppDatabase.seed(model: SeedModel = SeedGenerator().generate()): SeedModel = openHelper.writableDatabase.seed(model)

/** The seed writer on a raw [SupportSQLiteDatabase] (`TestRows.seed`), for data planted before the app starts. */
fun SupportSQLiteDatabase.seed(model: SeedModel = SeedGenerator().generate()): SeedModel {
    val db = this
    db.beginTransaction()
    try {
        db.statement("INSERT INTO persons (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)") { insert ->
            model.persons.forEach { insert.entity(it) }
        }
        db.statement("INSERT INTO projects (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)") { insert ->
            model.projects.forEach { insert.entity(it) }
        }
        db.statement(RAW_NOTE_INSERT) { insert -> model.notes.forEach { insert.note(it) } }
        db.statement("INSERT INTO note_persons (note_id, person_id) VALUES (?, ?)") { insert ->
            model.notes.forEach { note -> note.personIds.forEach { insert.link(note.id, it) } }
        }
        db.statement("INSERT INTO note_projects (note_id, project_id) VALUES (?, ?)") { insert ->
            model.notes.forEach { note -> note.projectIds.forEach { insert.link(note.id, it) } }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    assertDbInvariants(db)
    return model
}

private fun SupportSQLiteDatabase.statement(sql: String, block: (SupportSQLiteStatement) -> Unit) =
    compileStatement(sql).use(block)

private fun SupportSQLiteStatement.entity(entity: SeedEntity) {
    bindLong(1, entity.id)
    bindString(2, entity.name)
    bindLong(3, entity.createdAt)
    bindLong(4, entity.createdAt)
    executeInsert()
}

private fun SupportSQLiteStatement.note(note: SeedNote) {
    bindLong(1, note.id)
    if (note.date != null) bindLong(2, note.date) else bindNull(2)
    bindLong(3, if (note.checked) 1 else 0)
    if (note.checkedAt != null) bindLong(4, note.checkedAt) else bindNull(4)
    bindLong(5, note.createdAt)
    bindLong(6, note.updatedAt)
    bindString(7, note.content)
    bindString(8, SearchText.fold(note.content))
    executeInsert()
}

private fun SupportSQLiteStatement.link(noteId: Long, entityId: Long) {
    bindLong(1, noteId)
    bindLong(2, entityId)
    executeInsert()
}
