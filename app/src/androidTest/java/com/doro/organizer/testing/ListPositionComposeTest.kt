package com.doro.organizer.testing

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.doro.organizer.core.coroutines.AppDispatchers
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * A list-position test over the real app (testing.md I-58; DEC-110): the note repository counted and held through
 * [probe], the marker of a note just created recorded in [marker], and the `default` dispatcher, where the list maps
 * its rows, counted in [default]. Positions compare through [topRow] within 1 px; waits are condition waits only.
 */
abstract class ListPositionComposeTest : ListComposeTest() {
    protected lateinit var probe: RowsProbe
        private set

    protected val default = CountingDispatcher()

    protected val marker: RecordingNewNoteMarker get() = scene.app.container.newNoteMarker as RecordingNewNoteMarker

    init {
        scene.wrapNoteRepository = { real -> RowsProbe(real).also { probe = it } }
        scene.wrapNewNoteMarker = ::RecordingNewNoteMarker
        scene.dispatchers = AppDispatchers(main = Dispatchers.Main.immediate, io = Dispatchers.IO, default = default)
    }

    /**
     * [count] notes of one-line content ("note 0" newest) dated [date], each created and updated at least a minute
     * before now; [each] links them. Returns their ids in list order.
     */
    protected fun TestRows.notes(count: Int = PLANTED, date: Long? = null, each: TestRows.(Long) -> Unit = {}): List<Long> =
        List(count) { i -> note("note $i", createdAt = now - (i + 1) * MINUTE, date = date).also { each(it) } }

    /** + on the current list, [text] typed into the new note. */
    protected fun startNewNote(text: String = WORD) {
        editor.createNote()
        compose.onNodeWithTag(EditorUi.CONTENT).performTextInput(text)
    }

    /** The editor's toolbar Back. */
    protected fun tapBack() {
        compose.onNodeWithContentDescription(BACK).performClick()
    }

    /** The editor's Save action. */
    protected fun tapSave() {
        compose.onNodeWithContentDescription(SAVE).performClick()
    }

    /** Waits until the list is shown with the row of [id] on top. */
    protected fun awaitTopRow(id: Long) = compose.waitUntil(LONG_WAIT_MS) {
        runCatching { topRow().id == id }.getOrDefault(false)
    }

    /**
     * Waits until [hold] holds its call. Compose runs recomposition and effects only while the test synchronizes, and
     * the editor's save on leave starts from its entry's lifecycle, so this pumps frames instead of blocking.
     */
    protected fun awaitHeld(hold: RowsProbe.Hold) = compose.waitUntil(LONG_WAIT_MS) { hold.reached.isCompleted }

    /**
     * Holds the first emission of the re-read that the next `take()` of a note id starts (testing.md I-58 (u1), (u2)).
     * The list must still be collecting, or the hold would land on its restart; [awaitHeldReread] checks where it
     * landed.
     */
    protected fun holdReread(): RowsProbe.Hold {
        assertTrue("the list still collects: ${probe.collections}", probe.active().isNotEmpty())
        return probe.holdNextFirstEmission()
    }

    /** Waits until [hold] holds a collection, and checks that it is the re-read a `take()` of a note id started. */
    protected fun awaitHeldReread(hold: RowsProbe.Hold) {
        awaitHeld(hold)
        compose.waitUntil(LONG_WAIT_MS) { marker.takes.any { it.noteId != null } }
        val take = marker.takes.last { it.noteId != null }
        val held = checkNotNull(hold.collection)
        assertTrue("the held collection started before the take: $held", held.startedAt > take.at)
    }

    /**
     * The list's end less the create button's clearance, in whole px as the list's layout rounds its bottom content
     * padding: the end of a row's place shown entirely (ui.md § 3.11 rule 2).
     */
    protected fun wholeRowLimit(): Float =
        ui.boundsOf(NOTE_LIST).bottom - with(compose.density) { FAB_CLEARANCE.roundToPx() }

    /** Waits until no `observeRows` collection is active: the list stopped collecting (`WhileSubscribed(5_000)`). */
    protected fun awaitListStopped() = compose.waitUntil(LONG_WAIT_MS) { probe.active().isEmpty() }

    /**
     * The settle point after the editor's return (testing.md I-58): the marker shows a `take()` that returned a note id,
     * the list query's collection started after it has emitted, the `default` dispatcher has no task and Compose is
     * idle. Returns the note id taken.
     */
    protected fun awaitReveal(): Long {
        compose.waitUntil(LONG_WAIT_MS) { marker.takes.any { it.noteId != null } }
        val take = marker.takes.last { it.noteId != null }
        awaitRowsStartedAfter(take.at)
        return checkNotNull(take.noteId)
    }

    /** Waits until a collection started after [nanos] has emitted and its rows reached the screen. */
    protected fun awaitRowsStartedAfter(nanos: Long) {
        compose.waitUntil(LONG_WAIT_MS) {
            probe.collections.any { it.startedAt > nanos && it.emissions > 0 } && default.idle
        }
        compose.waitForIdle()
    }

    /** Every emission of `observeRows` so far, to wait for the next with [awaitRowsAfter]. */
    protected fun emissions(): Int = probe.collections.sumOf { it.emissions }

    /** Waits until `observeRows` emitted after the count [before] and the rows reached the screen. */
    protected fun awaitRowsAfter(before: Int) {
        compose.waitUntil(LONG_WAIT_MS) { emissions() > before && default.idle }
        compose.waitForIdle()
    }

    /** Waits until the popped editor has called `mark`, then checks that the marker kept none. */
    protected fun assertMarkIgnored() {
        compose.waitUntil(LONG_WAIT_MS) { marker.marks.isNotEmpty() }
        assertEquals("marks kept", emptyList<RecordingNewNoteMarker.Mark>(), marker.marks.filter { it.kept })
        assertNull("the marker holds no mark", marker.noteId.value)
    }

    /** Runs [block] and waits for the editor's detached work it started, then checks that no mark was kept. */
    protected fun assertNoMarkKeptAfter(block: () -> Unit) {
        awaitDetachedWork(block)
        compose.waitUntil(LONG_WAIT_MS) { default.idle }
        assertEquals("marks kept", emptyList<RecordingNewNoteMarker.Mark>(), marker.marks.filter { it.kept })
        assertNull("the marker holds no mark", marker.noteId.value)
    }

    companion object {
        const val PLANTED = 30
        const val MINUTE = 60_000L
        const val WORD = "word"
        const val LONG_WAIT_MS = 20_000L

        /** 56 dp button + 16 dp margin + 16 dp gap: the list's bottom content padding (ui.md § 2.3). */
        val FAB_CLEARANCE = 88.dp

        /** The single-column Calendar's items before its rows: the `MonthGrid` and the selection header. */
        const val CALENDAR_HEADER_ITEMS = 2
        const val MONTH_GRID = "month_grid"

        // Literals copied from ui.md § 11.
        const val BACK = "Back"
        const val SAVE = "Save"
    }
}
