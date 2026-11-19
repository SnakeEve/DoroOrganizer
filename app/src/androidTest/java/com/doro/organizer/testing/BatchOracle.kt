package com.doro.organizer.testing

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The Kotlin oracle of testing.md I-21 for batch actions over notes and their links to one [person]: it starts from
 * the database's current rows and applies the rules of data-layer.md § Batch operations and the `updated_at` matrix
 * of DEC-21 in plain Kotlin, without the SQL it checks. Each action returns the `changed` count it predicts.
 */
class BatchOracle(private val sql: SupportSQLiteDatabase, private val person: Long) {
    /** One note as the oracle sees it. */
    data class Note(val date: Long?, val updatedAt: Long, val linked: Boolean)

    private val notes: MutableMap<Long, Note> = read().toMutableMap()

    /** The predicted rows, by note id. */
    fun state(): Map<Long, Note> = notes.toMap()

    /** The actual rows, by note id. */
    fun read(): Map<Long, Note> {
        val linked = sql.longsOf("SELECT note_id FROM note_persons WHERE person_id = $person").toSet()
        return sql.query("SELECT id, date, updated_at FROM notes").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val date = if (cursor.isNull(1)) null else cursor.getLong(1)
                    put(id, Note(date, cursor.getLong(2), id in linked))
                }
            }
        }
    }

    /** Links [person] to [ids]; a note bumps only when it gains the link. */
    fun associate(ids: List<Long>, now: Long): Int = ids.count { id ->
        val note = notes.getValue(id)
        if (!note.linked) notes[id] = note.copy(linked = true, updatedAt = now)
        !note.linked
    }

    /** Sets [date] on [ids]; a note is written and bumped only when its date changes. */
    fun setDate(ids: List<Long>, date: Long?, now: Long): Int = ids.count { id ->
        val note = notes.getValue(id)
        if (note.date != date) notes[id] = note.copy(date = date, updatedAt = now)
        note.date != date
    }

    /** Deletes [ids] with their links. */
    fun delete(ids: List<Long>): Int = ids.count { notes.remove(it) != null }
}
