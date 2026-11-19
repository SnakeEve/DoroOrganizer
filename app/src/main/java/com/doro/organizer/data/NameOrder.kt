package com.doro.organizer.data

import com.doro.organizer.model.EntityRef
import java.text.Collator

/**
 * The picker order of DEC-32: `Collator` on the name, then id. Pickers, row links and editor links share it
 * (data-layer.md § Rows). SQLite cannot sort that way (no ICU, DEC-08). Call once per pass: a `Collator` is not
 * thread-safe, and the default locale is read again on each call.
 */
internal fun <T> nameOrder(name: (T) -> String, id: (T) -> Long): Comparator<T> {
    val collator = Collator.getInstance()
    return Comparator<T> { a, b -> collator.compare(name(a), name(b)) }.thenBy { id(it) }
}

/** [nameOrder] for the person and project links of a note. */
internal fun linkOrder(): Comparator<EntityRef> = nameOrder({ it.name }, { it.id })
