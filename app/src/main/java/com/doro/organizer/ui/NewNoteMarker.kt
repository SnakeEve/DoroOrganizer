package com.doro.organizer.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The mark of a note just created, for the list its editor returns to (DEC-110, ui.md § 3.11 rule 2). It holds the
 * note id and nothing else, in memory only: never in a `SavedStateHandle`, a navigation key or the settings, so it is
 * gone with the process. Application-scoped (`UiGraph.newNoteMarker`); main thread only.
 *
 * `Navigator.back()` [expect]s the `visit` of the new note's editor it pops; the editor's `onCleared` [mark]s with
 * that `visit` after its last save; the list that is the top entry [take]s it once. Every other navigation and a data
 * reset [clear] it.
 */
interface NewNoteMarker {
    /** The current mark; null when there is none. */
    val noteId: StateFlow<Long?>

    /** The editor of `visit`, a new note's, was popped: its mark is the one to keep. */
    fun expect(visit: Int)

    /** Marks [noteId] for the editor of [visit]; kept only when [visit] is the expected one, replacing an older mark. */
    fun mark(noteId: Long, visit: Int)

    /** The mark, once: it is cleared. */
    fun take(): Long?

    /** Drops the mark and the expectation. */
    fun clear()
}

/** The production [NewNoteMarker]. */
class DefaultNewNoteMarker : NewNoteMarker {
    private val mark = MutableStateFlow<Long?>(null)
    private var expected: Int? = null

    override val noteId: StateFlow<Long?> = mark.asStateFlow()

    override fun expect(visit: Int) {
        expected = visit
    }

    override fun mark(noteId: Long, visit: Int) {
        if (visit == expected) mark.value = noteId
    }

    override fun take(): Long? = mark.value.also { mark.value = null }

    override fun clear() {
        expected = null
        mark.value = null
    }
}
