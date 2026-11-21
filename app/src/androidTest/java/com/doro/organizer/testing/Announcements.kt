package com.doro.organizer.testing

import com.doro.organizer.ui.common.Announcer
import java.util.concurrent.CopyOnWriteArrayList

/** Records every announcement the app's screens make (ui.md § 12.3) and passes it on to the real [Announcer]. */
class Announcements {
    private val texts = CopyOnWriteArrayList<String>()

    /** Every announced text so far, in order. */
    val all: List<String> get() = texts.toList()

    fun count(text: String): Int = texts.count { it == text }

    fun wrap(inner: Announcer): Announcer = Announcer { text ->
        texts += text
        inner.announce(text)
    }

    override fun toString() = "Announcements(${texts.size})"
}
