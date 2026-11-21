package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.NoteRowUi

/** A `LazyColumn` scroll position: the first visible item and its scroll offset. */
internal data class ScrollPosition(val index: Int, val offset: Int) {
    override fun toString() = "ScrollPosition($index, $offset)"
}

/**
 * Keeps the viewport still when the first visible row is toggled (ui.md § 3.9 item 4). `LazyColumn` follows its first
 * visible item by key, so without this the list would scroll along with the moving row. The position recorded before
 * the write is restored only for the rows that carry the write's result, the toggled row with its new done state; a
 * write that changed nothing drops it, so a later, unrelated rows change never jumps the list. Plain fields, not
 * state: it is read and cleared during composition.
 */
internal class ToggleAnchor {
    private var pending: Pending? = null

    /** Before the write that sets [checked] on row [id], with the list showing [rows] at [position]. */
    fun record(id: Long, checked: Boolean, position: ScrollPosition, rows: List<NoteRowUi>) {
        pending = Pending(id, checked, position, rows)
    }

    /** The write of row [id] changed nothing (it failed, or the row already had that state): no emission follows. */
    fun drop(id: Long) {
        if (pending?.id == id) pending = null
    }

    /**
     * The position to restore when the list is about to show [rows]: the recorded one once [rows] hold the toggled
     * row in its new state. Null otherwise; an earlier, unrelated change keeps the anchor, and a list without the
     * row ends it.
     */
    fun positionFor(rows: List<NoteRowUi>): ScrollPosition? {
        val toggle = pending ?: return null
        if (rows === toggle.rowsBefore) return null
        val row = rows.firstOrNull { it.id == toggle.id }
        if (row != null && row.checked != toggle.checked) return null
        pending = null
        return toggle.position.takeIf { row != null }
    }

    private class Pending(
        val id: Long,
        val checked: Boolean,
        val position: ScrollPosition,
        val rowsBefore: List<NoteRowUi>,
    )
}
