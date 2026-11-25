package com.doro.organizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** testing.md I-59 (a): the mark of a note just created is kept only for the popped editor and used once (DEC-110). */
class NewNoteMarkerTest {
    private val marker = DefaultNewNoteMarker()

    @Test
    fun aMarkWithTheExpectedVisit_isKept_andTakenOnce() {
        marker.expect(4)

        marker.mark(noteId = 21, visit = 4)

        assertEquals(21L, marker.noteId.value)
        assertEquals(21L, marker.take())
        assertNull(marker.noteId.value)
        assertNull(marker.take())
    }

    @Test
    fun aMarkWithoutThatExpectation_isIgnored() {
        marker.mark(noteId = 21, visit = 4)
        assertNull("nothing expected", marker.noteId.value)

        marker.expect(5)
        marker.mark(noteId = 21, visit = 4)

        assertNull("another editor's visit", marker.noteId.value)
        assertNull(marker.take())
    }

    @Test
    fun aNewerMark_replacesAnOlderOne() {
        marker.expect(4)
        marker.mark(noteId = 21, visit = 4)

        marker.mark(noteId = 22, visit = 4)

        assertEquals(22L, marker.take())
    }

    @Test
    fun clear_dropsTheMarkAndTheExpectation() {
        marker.expect(4)
        marker.mark(noteId = 21, visit = 4)

        marker.clear()
        assertNull(marker.noteId.value)
        marker.mark(noteId = 22, visit = 4)

        assertNull("the expectation went with the mark", marker.noteId.value)
        assertNull(marker.take())
    }
}
