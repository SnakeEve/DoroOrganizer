package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.NoteRowUi

/** A reveal waiting for its rows (DEC-110 (2)): the note to show and the read whose rows are judged. */
internal data class NewNoteWait(val noteId: Long, val read: Int) {
    override fun toString() = "NewNoteWait(noteId=$noteId, read=$read)"
}

/**
 * The reveal of a note just created in the list its editor returns to (DEC-110 (2), ui.md § 3.11 rule 2). It keeps
 * the [NewNoteWait] that `NoteListViewModel.takeNewNote()` returned and judges the first rows of that read or a later
 * one, once: they give the position that shows the note's row, or nothing when they lack the note or a drag or fling
 * is in progress. Rows of an earlier read keep the wait. Plain fields, not state: it is judged during composition.
 */
internal class NewNoteReveal {
    private var wait: NewNoteWait? = null

    /** Starts waiting for [wait]; a later wait replaces an earlier one. */
    fun await(wait: NewNoteWait) {
        this.wait = wait
    }

    /** Ends the wait without a move: a drag started on the list, or the picker body shows. */
    fun drop() {
        wait = null
    }

    /**
     * The position that reveals the note when [rows] of read [rowsRead] end the wait, for a list laid out as [layout]
     * with [headerItems] items before its rows: the note's row at the top of the screen. When it is the first row of a
     * list at its very top, item 0 instead, which keeps the header items above it, but only when the first row's place
     * was shown entirely (the single-column Calendar's grid). Null when the rows do not end the wait or lack the note.
     */
    fun positionFor(rows: List<NoteRowUi>, rowsRead: Int, layout: LastLayout, headerItems: Int): ScrollPosition? {
        val index = rowIndexFor(rows, rowsRead, layout.scrollInProgress) ?: return null
        val keepsTop = index == 0 && layout.atVeryTop && layout.lastWholeIndex >= headerItems
        return if (keepsTop) ScrollPosition(0, 0) else ScrollPosition(headerItems + index, 0)
    }

    private fun rowIndexFor(rows: List<NoteRowUi>, rowsRead: Int, scrollInProgress: Boolean): Int? {
        val current = wait ?: return null
        if (rowsRead < current.read) return null
        wait = null
        if (scrollInProgress) return null
        return rows.indexOfFirst { it.id == current.noteId }.takeIf { it >= 0 }
    }
}
