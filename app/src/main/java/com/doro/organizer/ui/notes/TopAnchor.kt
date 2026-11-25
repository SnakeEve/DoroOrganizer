package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.NoteRowUi

/**
 * Keeps a list at its very top there when rows arrive above its first row (DEC-110 (1), ui.md § 3.11 rule 1): a new
 * note, a note edited or unchecked, rows a cleared filter or search brings back. Key anchoring would keep the old
 * first row on top and leave them above the screen. Plain fields, not state: it is read and updated during composition.
 */
internal class TopAnchor {
    private var lastRows: List<NoteRowUi>? = null

    /**
     * Item 0 at offset 0 when [rows] has another first row than the rows seen before and [layout] is at the very top
     * with no scroll in progress; null otherwise, also for the first rows it sees.
     */
    fun positionFor(rows: List<NoteRowUi>, layout: LastLayout): ScrollPosition? {
        val previous = lastRows
        if (rows === previous) return null
        lastRows = rows
        if (previous == null || rows.firstOrNull()?.id == previous.firstOrNull()?.id) return null
        return if (layout.atVeryTop && !layout.scrollInProgress) TOP else null
    }

    private companion object {
        val TOP = ScrollPosition(0, 0)
    }
}
