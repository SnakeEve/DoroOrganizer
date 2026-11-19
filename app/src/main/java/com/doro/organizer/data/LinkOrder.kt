package com.doro.organizer.data

import com.doro.organizer.model.EntityRef
import java.text.Collator

/**
 * The order of a note's person and project links: `Collator` on the name, then id, the picker order of DEC-32.
 * SQLite cannot sort that way (no ICU, DEC-08). Call once per pass: a `Collator` is not thread-safe, and the default
 * locale is read again on each call.
 */
internal fun linkOrder(): Comparator<EntityRef> {
    val collator = Collator.getInstance()
    return Comparator<EntityRef> { a, b -> collator.compare(a.name, b.name) }.thenBy { it.id }
}
