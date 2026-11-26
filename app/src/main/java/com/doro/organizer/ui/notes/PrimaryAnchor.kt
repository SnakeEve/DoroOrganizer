package com.doro.organizer.ui.notes

import androidx.compose.runtime.saveable.Saver

/**
 * The primary that a list's scroll position belongs to (DEC-110 (4), ui.md § 3.11 rule 4): the entity, the day or the
 * range it showed last, or the picker body, which [pickerBody] records while it shows, so whatever entity comes next is
 * new, the earlier one included. It is saved with the position ([Saver]: `e:<id>`, `d:<epochDay>`, `r:<from>:<to>` or
 * `b`), so a covering picker, a tab switch, a rotation, a lock or a process death keep it. Plain fields, not state: it
 * is read and updated during composition.
 */
internal class PrimaryAnchor private constructor(private var record: String?) {
    constructor() : this(null)

    /** The picker body shows in place of the list. */
    fun pickerBody() {
        record = BODY
    }

    /**
     * Records [primary] when it is decided, and tells whether it replaced another record: true for another entity,
     * day or range, also after a restored record or [pickerBody]; false for `Loading`, `None`, the first decided
     * primary of an anchor with no record, and the same one again (a rename keeps the id).
     */
    fun changedTo(primary: Primary): Boolean {
        val next = recordOf(primary) ?: return false
        val previous = record
        record = next
        return previous != null && previous != next
    }

    companion object {
        private const val BODY = "b"

        /** Saves the record, which holds ids and days only. */
        val Saver: Saver<PrimaryAnchor, String> = Saver(save = { it.record }, restore = { PrimaryAnchor(it) })

        /**
         * Where a new primary's list starts (DEC-110 (4)): item 0 at offset 0 in a list without [headerItems], or when
         * a row is its first visible item ([firstVisibleIndex]); null while the single-column Calendar shows its grid or
         * its selection header, which stay where the finger tapped them.
         */
        fun startOf(headerItems: Int, firstVisibleIndex: Int): ScrollPosition? =
            if (headerItems == 0 || firstVisibleIndex >= headerItems) ScrollPosition(0, 0) else null

        private fun recordOf(primary: Primary): String? = when (primary) {
            Primary.Loading, Primary.None -> null
            is Primary.Entity -> "e:${primary.id}"
            is Primary.Day -> "d:${primary.epochDay}"
            is Primary.Range -> "r:${primary.fromDay}:${primary.toDay}"
        }
    }
}
