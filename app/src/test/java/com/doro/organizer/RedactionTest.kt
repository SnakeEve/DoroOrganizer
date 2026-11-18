package com.doro.organizer

import com.doro.organizer.data.db.NoteEntity
import com.doro.organizer.data.db.NotePersonCrossRef
import com.doro.organizer.data.db.NoteProjectCrossRef
import com.doro.organizer.data.db.PersonEntity
import com.doro.organizer.data.db.ProjectEntity
import com.doro.organizer.testing.SENTINEL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * testing.md I-39 (DEC-70): each class is built with [SENTINEL] in every `String` field, and its `toString()` prints
 * ids only. Grows with every class that I-39 lists.
 */
class RedactionTest {
    @Test
    fun noteEntity_printsIdOnly() {
        val note = NoteEntity(
            id = 7,
            date = 20_000,
            checked = true,
            checkedAt = 3,
            createdAt = 1,
            updatedAt = 2,
            content = SENTINEL,
            contentFold = SENTINEL,
        )

        assertEquals("NoteEntity(id=7)", note.toString())
    }

    @Test
    fun personEntity_printsIdOnly() {
        val person = PersonEntity(
            id = 8,
            name = SENTINEL,
            role = SENTINEL,
            remarks = SENTINEL,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals("PersonEntity(id=8)", person.toString())
    }

    @Test
    fun projectEntity_printsIdOnly() {
        val project = ProjectEntity(
            id = 9,
            name = SENTINEL,
            description = SENTINEL,
            status = SENTINEL,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals("ProjectEntity(id=9)", project.toString())
    }

    @Test
    fun crossRefs_printIdsOnly() {
        assertEquals("NotePersonCrossRef(noteId=1, personId=2)", NotePersonCrossRef(1, 2).toString())
        assertEquals("NoteProjectCrossRef(noteId=3, projectId=4)", NoteProjectCrossRef(3, 4).toString())
    }
}
