package com.doro.organizer

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.CreatedIds
import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.data.db.NoteDetailRow
import com.doro.organizer.data.db.NoteEntity
import com.doro.organizer.data.db.NotePersonCrossRef
import com.doro.organizer.data.db.NoteProjectCrossRef
import com.doro.organizer.data.db.PersonEntity
import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.data.db.ProjectEntity
import com.doro.organizer.data.db.SaveProbe
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.SaveResult
import com.doro.organizer.testing.SENTINEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun noteRowRaw_printsIdOnly() {
        val raw = NoteRowRaw(
            id = 10,
            preview = SENTINEL,
            date = null,
            checked = false,
            checkedAt = null,
            createdAt = 1,
            updatedAt = 2,
            personsJson = SENTINEL,
            projectsJson = SENTINEL,
        )

        assertEquals("NoteRowRaw(id=10)", raw.toString())
    }

    @Test
    fun noteDetailRow_printsIdOnly() {
        val row = NoteDetailRow(
            id = 11,
            content = SENTINEL,
            date = null,
            checked = false,
            checkedAt = null,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals("NoteDetailRow(id=11)", row.toString())
    }

    @Test
    fun entityOptionRow_printsIdOnly() {
        assertEquals("EntityOptionRow(id=12)", EntityOptionRow(12, SENTINEL, 1, 3, true).toString())
    }

    @Test
    fun noteRow_printsIdOnly() {
        val links = listOf(EntityRef(1, SENTINEL))
        val row = NoteRow(
            id = 13,
            preview = SENTINEL,
            date = null,
            checked = false,
            checkedAt = null,
            createdAt = 1,
            updatedAt = 2,
            persons = links,
            projects = links,
        )

        assertEquals("NoteRow(id=13)", row.toString())
    }

    @Test
    fun entityRef_printsIdOnly() {
        assertEquals("EntityRef(id=14)", EntityRef(14, SENTINEL).toString())
    }

    @Test
    fun noteFilter_printsSearchLengthOnly() {
        val filter = NoteFilter(
            perspective = Perspective.Date,
            primaryDays = DateFilter.Range(5, 6),
            search = SENTINEL,
        )

        val text = filter.toString()
        assertFalse(text, text.contains(SENTINEL))
        assertEquals(
            "NoteFilter(perspective=Date, primaryId=null, primaryDays=Range(fromDay=5, toDay=6), personId=null, " +
                "projectId=null, date=Any, search=<${SENTINEL.length} chars>)",
            text,
        )
    }

    @Test
    fun noteQuery_printsPatternLengthOnly() {
        val query = NoteQuery(1, 2, orphanOnly = false, undatedOnly = true, fromDay = 3, toDay = 4, SENTINEL)

        val text = query.toString()
        assertFalse(text, text.contains(SENTINEL))
        assertEquals(
            "NoteQuery(personId=1, projectId=2, orphanOnly=false, undatedOnly=true, fromDay=3, toDay=4, " +
                "searchPattern=<${SENTINEL.length} chars>)",
            text,
        )
    }

    @Test
    fun noteDraft_printsNoteIdOnly() {
        val entities = listOf(NewEntity("k1", SENTINEL))
        val draft = NoteDraft(
            draftKey = SENTINEL,
            noteId = 15,
            content = SENTINEL,
            date = null,
            checked = false,
            personIds = setOf(1),
            projectIds = setOf(2),
            newPersons = entities,
            newProjects = entities,
        )

        assertEquals("NoteDraft(noteId=15)", draft.toString())
    }

    @Test
    fun newEntity_printsKeyOnly() {
        assertEquals("NewEntity(key=k1)", NewEntity("k1", SENTINEL).toString())
    }

    @Test
    fun saved_printsIdsAndCountsOnly() {
        val saved = SaveResult.Saved(16, wrote = true, mapOf(SENTINEL to 1L), mapOf(SENTINEL to 2L, "b" to 3L))

        assertEquals("Saved(noteId=16, wrote=true, createdPersons=1, createdProjects=2)", saved.toString())
    }

    @Test
    fun createdIds_printsCountsOnly() {
        assertEquals("CreatedIds(persons=1, projects=0)", CreatedIds(mapOf(SENTINEL to 1L), emptyMap()).toString())
    }

    @Test
    fun saveProbe_printsClassNameOnly() {
        assertEquals("SaveProbe", SaveProbe(SENTINEL, 20_000).toString())
    }

    @Test
    fun noteDetail_printsIdOnly() {
        val links = listOf(EntityRef(1, SENTINEL))
        val detail = NoteDetail(
            id = 17,
            content = SENTINEL,
            date = 20_000,
            checked = true,
            checkedAt = 3,
            createdAt = 1,
            updatedAt = 2,
            persons = links,
            projects = links,
        )

        assertEquals("NoteDetail(id=17)", detail.toString())
    }

    @Test
    fun appError_printsTheCaseOnly() {
        assertEquals("InvalidInput(rule=BlankName)", AppError.InvalidInput(InputRule.BlankName).toString())
    }

    @Test
    fun batchResult_printsCounts() {
        assertEquals("BatchResult(selected=3, changed=2)", BatchResult(3, 2).toString())
    }
}
