package com.doro.organizer.ui.editor

import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's save-on-leave rule (ui.md § 6.8, DEC-37; the ViewModel side of testing.md I-36) and the saved form of
 * its dialogs (ui.md § 6.14, key `editorDialog`; I-38).
 */
class EditorDraftTest {
    private val prefilled = fields(personIds = setOf(3), date = 20_774)
    private val initial = prefilled.baseline("")

    @Test
    fun aNewBlankDraft_isNotSaved() {
        val blank = fields()

        assertFalse(savesOnLeave(blank, "", blank.baseline("")))
    }

    @Test
    fun aNewDraftHoldingOnlyItsPrefill_isNotSaved() {
        assertFalse(savesOnLeave(prefilled, "", initial))
    }

    @Test
    fun aRemovedPrefill_differsButIsBlank_soItIsNotSaved() {
        assertFalse(savesOnLeave(fields(), "", initial))
    }

    @Test
    fun aNewDraft_isSavedForAnyContent_includingWhitespace() {
        assertTrue(savesOnLeave(prefilled, "x", initial))
        assertTrue(savesOnLeave(prefilled, " ", initial))
    }

    @Test
    fun aNewDraft_isSavedForAChangedDate_link_check_orCreatedEntity() {
        assertTrue(savesOnLeave(prefilled.copy(date = 20_775), "", initial))
        assertTrue(savesOnLeave(prefilled.copy(projectIds = setOf(4)), "", initial))
        assertTrue(savesOnLeave(prefilled.copy(checked = true), "", initial))
        assertTrue(savesOnLeave(prefilled.copy(newProjects = listOf(NewEntity("k", "Alpha"))), "", initial))
    }

    @Test
    fun aStoredNote_isSavedOnlyWhenContentDateOrLinksDiffer() {
        val stored = fields(noteId = 1, personIds = setOf(3))
        val base = stored.baseline("text")

        assertFalse(savesOnLeave(stored, "text", base))
        assertFalse("checked is written at once, never by a save", savesOnLeave(stored.copy(checked = true), "text", base))
        assertTrue(savesOnLeave(stored, "text ", base))
        assertTrue(savesOnLeave(stored.copy(date = 1), "text", base))
        assertTrue(savesOnLeave(stored.copy(personIds = emptySet()), "text", base))
        assertTrue(savesOnLeave(stored.copy(newPersons = listOf(NewEntity("k", "Bob"))), "text", base))
    }

    @Test
    fun aRevertedEdit_isNoChange() {
        val stored = fields(noteId = 1)

        assertFalse(savesOnLeave(stored.copy(date = null), "same", stored.baseline("same")))
    }

    @Test
    fun withoutAnEntity_dropsItFromTheBaseline() {
        val base = fields(personIds = setOf(1, 2), projectIds = setOf(1)).baseline("")

        assertEquals(setOf(2L), base.without(EntityKind.Person, 1).personIds)
        assertEquals(setOf(1L), base.without(EntityKind.Person, 1).projectIds)
        assertEquals(emptySet<Long>(), base.without(EntityKind.Project, 1).projectIds)
    }

    @Test
    fun everyDialog_roundTripsThroughItsKey_andAnImpactKeepsOnlyKindAndId() {
        val plain = listOf(EditorDialog.DeleteNote, EditorDialog.Unlink, EditorDialog.DatePicker)
        val impact = EditorDialog.DeleteImpact(EntityKind.Project, 12, "Alpha", n = 3, m = 1)

        assertEquals(listOf("DeleteNote", "Unlink", "DatePicker"), plain.map { it.toKey() })
        assertEquals(plain.map { SavedDialog.Plain(it) }, plain.map { savedDialogOf(it.toKey()) })
        assertEquals("DeleteImpact:Project:12", impact.toKey())
        assertEquals(SavedDialog.Impact(EntityKind.Project, 12), savedDialogOf(impact.toKey()))
    }

    @Test
    fun noKeyOrAnUnknownKey_isNoDialog() {
        assertNull(savedDialogOf(null))
        assertNull(savedDialogOf("Discard"))
        assertNull(savedDialogOf("DeleteImpact:Team:1"))
        assertNull(savedDialogOf("DeleteImpact:Person:x"))
        assertNull(savedDialogOf("DeleteImpact:Person"))
    }

    private fun fields(
        noteId: Long? = null,
        date: Long? = null,
        personIds: Set<Long> = emptySet(),
        projectIds: Set<Long> = emptySet(),
    ) = DraftFields(noteId, date, false, personIds, projectIds, emptyList(), emptyList())
}
