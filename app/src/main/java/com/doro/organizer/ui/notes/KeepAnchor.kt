package com.doro.organizer.ui.notes

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot
import com.doro.organizer.ui.common.NoteRowUi

/**
 * What the list showed in its last layout, as the position rules read it (ui.md § 3.11): the first visible item's
 * [index], [offset] and [key] (null before the first layout), whether a drag or fling is in progress, and the last item
 * shown entirely above the bottom content padding ([lastWholeIndex], -1 for none).
 */
internal class LastLayout(
    val index: Int,
    val offset: Int,
    val key: Any?,
    val scrollInProgress: Boolean,
    val lastWholeIndex: Int,
) {
    /** The first visible item is item 0 at offset 0 (DEC-110 (1)). */
    val atVeryTop: Boolean get() = index == 0 && offset == 0

    /** The first visible item's index and offset. */
    val scrollPosition: ScrollPosition get() = ScrollPosition(index, offset)

    override fun toString() = "LastLayout($index, $offset, scrolling=$scrollInProgress)"

    companion object {
        /**
         * [state]'s last layout, read without observation: the rules run in composition, which must not recompose on
         * every scroll frame.
         */
        fun of(state: LazyListState): LastLayout = Snapshot.withoutReadObservation {
            of(state.layoutInfo, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress)
        }

        /**
         * The layout [info] with its first visible item [index] at [offset]. An item is shown entirely when it ends at
         * or above `viewportEndOffset - afterContentPadding`, the reveal's test for the single-column Calendar's first
         * row (DEC-110 (2)).
         */
        fun of(info: LazyListLayoutInfo, index: Int, offset: Int, scrollInProgress: Boolean): LastLayout {
            val wholeEnd = info.viewportEndOffset - info.afterContentPadding
            return LastLayout(
                index = index,
                offset = offset,
                key = info.visibleItemsInfo.firstOrNull { it.index == index }?.key,
                scrollInProgress = scrollInProgress,
                lastWholeIndex = info.visibleItemsInfo.lastOrNull { it.offset + it.size <= wholeEnd }?.index ?: -1,
            )
        }
    }
}

/** The row first on screen when a list left composition (DEC-110 (3)): its id, done state and `orderedAt`. */
internal data class KeptRow(val id: Long, val checked: Boolean, val orderedAt: Long) {
    /** [row] is this row in the same place of the canonical order. */
    fun unmoved(row: NoteRowUi) = row.checked == checked && row.orderedAt == orderedAt

    override fun toString() = "KeptRow(id=$id)"

    companion object {
        /** The row of [rows] that [layout] shows first; null when a header item or no row is first. */
        fun of(layout: LastLayout, rows: List<NoteRowUi>): KeptRow? =
            rows.firstOrNull { it.id == layout.key }?.let { KeptRow(it.id, it.checked, it.orderedAt) }
    }
}

/**
 * Keeps a list that is not at its very top where it is, also for edits (DEC-110 (3), ui.md § 3.11 rule 3). Key
 * anchoring keeps the first visible row first on screen while it stays in place; when that row moved (its done state or
 * `orderedAt` changed), this asks for the last layout's index and offset instead, as [ToggleAnchor] does. Across a
 * return, where `LazyListState` restores only the index and offset, [seed] is the row the list showed first when it
 * left and [restored] the restored index and offset, read before any layout: a layout of rows that are still empty or
 * loading clamps the list's own position. The first rows that hold rows put the seed back at the restored offset when
 * it is there unmoved, and otherwise ask for the restored position. Plain fields, not state: it is read and updated
 * during composition.
 */
internal class KeepAnchor(private var seed: KeptRow?, private val restored: ScrollPosition) {
    private var lastRows: List<NoteRowUi>? = null

    /** The position to ask for when the list, laid out as [layout], is about to show [rows] after [headerItems] items. */
    fun positionFor(rows: List<NoteRowUi>, layout: LastLayout, headerItems: Int): ScrollPosition? {
        val previous = lastRows
        if (rows === previous) return null
        lastRows = rows
        seed?.let { kept ->
            if (rows.isEmpty()) return null
            seed = null
            // At the restored very top rule 1 (TopAnchor) applies.
            if (restored.index == 0 && restored.offset == 0 || layout.scrollInProgress) return null
            val index = rows.indexOfFirst { it.id == kept.id }
            if (index < 0 || !kept.unmoved(rows[index])) return restored
            return ScrollPosition(headerItems + index, restored.offset)
        }
        if (previous == null || layout.atVeryTop || layout.scrollInProgress) return null
        val before = previous.firstOrNull { it.id == layout.key } ?: return null
        val after = rows.firstOrNull { it.id == before.id } ?: return null
        val kept = KeptRow(before.id, before.checked, before.orderedAt)
        return if (kept.unmoved(after)) null else ScrollPosition(layout.index, layout.offset)
    }

    /**
     * The seed while no rows have used it, so a list that leaves before its first rows hands it on instead of the row
     * of a layout that showed none.
     */
    fun unusedSeed(): KeptRow? = seed
}
