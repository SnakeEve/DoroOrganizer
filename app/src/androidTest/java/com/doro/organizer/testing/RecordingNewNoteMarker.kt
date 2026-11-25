package com.doro.organizer.testing

import com.doro.organizer.ui.NewNoteMarker
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's real [NewNoteMarker], recording every call (testing.md I-58, I-59 (i); DEC-110): [expects], [marks] with
 * whether the mark was kept, [takes] with what each returned, and the number of [clears]. A mark counts as kept when
 * its `visit` is the expected one, the marker's rule, tracked here from the calls, so a mark of the id the marker
 * already holds is still told apart.
 */
class RecordingNewNoteMarker(private val real: NewNoteMarker) : NewNoteMarker by real {
    /** One `mark` call and whether the marker kept it. */
    data class Mark(val noteId: Long, val visit: Int, val kept: Boolean)

    /** One `take` call, what it returned and when (`System.nanoTime()`). */
    data class Take(val noteId: Long?, val at: Long)

    val expects: List<Int> get() = expectCalls.toList()
    val marks: List<Mark> get() = markCalls.toList()
    val takes: List<Take> get() = takeCalls.toList()
    val clears: Int get() = clearCalls.size

    private val expectCalls = CopyOnWriteArrayList<Int>()
    private val markCalls = CopyOnWriteArrayList<Mark>()
    private val takeCalls = CopyOnWriteArrayList<Take>()
    private val clearCalls = CopyOnWriteArrayList<Unit>()

    @Volatile private var expected: Int? = null

    override fun expect(visit: Int) {
        real.expect(visit)
        expected = visit
        expectCalls += visit
    }

    override fun mark(noteId: Long, visit: Int) {
        real.mark(noteId, visit)
        markCalls += Mark(noteId, visit, kept = visit == expected)
    }

    override fun take(): Long? = real.take().also { takeCalls += Take(it, System.nanoTime()) }

    override fun clear() {
        real.clear()
        expected = null
        clearCalls += Unit
    }
}
