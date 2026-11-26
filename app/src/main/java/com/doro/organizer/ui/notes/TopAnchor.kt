package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.NoteRowUi
import com.doro.organizer.ui.common.PickerItem

/**
 * Keeps a list at its very top there when rows arrive above its first row (DEC-110 (1), ui.md § 3.11 rule 1): a new
 * note, a note edited or unchecked, rows a cleared filter or search brings back. Key anchoring would keep the old
 * first row on top and leave them above the screen. The picker body of Individual and Project applies the same rule to
 * its items (DEC-112 (4)). One instance serves one list. Plain fields, not state: it is read and updated during
 * composition.
 */
internal class TopAnchor {
    private var seen: List<*>? = null
    private var seenFirstKey: Any? = null

    /**
     * Item 0 at offset 0 when [rows] has another first row than the rows seen before and [layout] is at the very top
     * with no scroll in progress; null otherwise, also for the first rows it sees.
     */
    fun positionFor(rows: List<NoteRowUi>, layout: LastLayout): ScrollPosition? =
        positionFor(rows, rows.firstOrNull()?.id, layout.atRestAtTop)

    /**
     * The picker body's rule (DEC-112 (4)): item 0 at offset 0 when [items] has another first key than the items seen
     * before and the body's list was [atTop] (its last layout at the very top with no scroll in progress,
     * [LastLayout.atRestAtTop]); null otherwise, also for the first items it sees. The body first shows the names its
     * ViewModel had when it was last shown, then fresh ones, which key anchoring would leave above the screen.
     */
    fun positionForPicker(items: List<PickerItem>, atTop: Boolean): ScrollPosition? =
        positionFor(items, items.firstOrNull()?.key, atTop)

    private fun positionFor(list: List<*>, firstKey: Any?, atTop: Boolean): ScrollPosition? {
        val previous = seen
        if (list === previous) return null
        val previousFirstKey = seenFirstKey
        seen = list
        seenFirstKey = firstKey
        if (previous == null || firstKey == previousFirstKey) return null
        return if (atTop) TOP else null
    }

    private companion object {
        val TOP = ScrollPosition(0, 0)
    }
}
