package com.doro.organizer.ui.notes

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.showAlone
import com.doro.organizer.ui.common.NoteRowUi
import com.doro.organizer.ui.common.rememberDateFormats
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-64 (c1)–(c3): rule 4 of ui.md § 3.11 (DEC-110 (4)) in `NoteListBody` alone, over hand-made states. In
 * the composition that first shows a new primary, the list asks for its top before the toggle anchor, and ends a kept
 * row's seed and a reveal's wait that its rows would otherwise use.
 */
@RunWith(AndroidJUnit4::class)
class NoteListBodyRulesUiTest : ScriptedGateComposeTest() {
    private val state = mutableStateOf(stateOf(ALICE, rows(ALICE_NOTES)))
    private val reveal = NewNoteReveal()
    private var keptRow: KeptRow? = null
    private lateinit var listState: LazyListState

    @Test
    fun c1_aToggleAnchorRecordedOnTheOldPrimary_givesWayToTheNewPrimarysTop() {
        show(PrimaryAnchor())
        scrollTo(SCROLLED)
        val toggled = ALICE_NOTES[SCROLLED]

        compose.onNodeWithTag(ListComposeTest.checkboxTag(toggled), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        // Bob's first rows hold the toggled row, done now, as their last row. NoteListViewModel sends a loading state
        // first, whose empty rows end the anchor, but Compose may skip it: rule 4 must come first for any rows value.
        val bobs = rows(BOB_NOTES) + row(toggled, checked = true)
        compose.runOnIdle { state.value = stateOf(BOB, bobs) }

        assertTop()
    }

    @Test
    fun c2_aKeptRowSeed_isDropped_whenTheNewPrimarysRowsHoldItUnmoved() {
        val kept = ALICE_NOTES[SCROLLED]
        keptRow = KeptRow(kept, checked = false, orderedAt = orderedAt(kept))
        state.value = loading(BOB)

        // The record of Alice, as restored with the position; the list restored at the kept row.
        show(PrimaryAnchor().apply { changedTo(ALICE) }, initialIndex = SCROLLED)
        compose.runOnIdle { state.value = stateOf(BOB, rows(listOf(9_001L, 9_002L) + ALICE_NOTES)) }

        assertTop()
    }

    @Test
    fun c3_aRevealsWait_endsWithTheNewPrimary_evenWhenItsRowsHoldTheNoteAsTheLastRow() {
        val done = 9_100L
        reveal.await(NewNoteWait(done, read = 1))
        show(PrimaryAnchor())
        compose.runOnIdle { state.value = loading(BOB) }

        compose.runOnIdle { state.value = stateOf(BOB, rows(BOB_NOTES) + row(done, checked = true), rowsRead = 1) }

        assertTop()
    }

    /** Shows `NoteListBody` alone over [state], its position rules through [reveal] and [keptRow]. */
    private fun show(anchor: PrimaryAnchor, initialIndex: Int = 0) {
        val position = ListPositionHooks(reveal, takeKeptRow = { keptRow.also { keptRow = null } }, keepRow = {})
        showAlone {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            NoteListBody(
                state = state.value,
                listState = listState,
                formats = rememberDateFormats(),
                onCheckedChange = { _, _, onDone -> onDone(true) },
                rowActions = RowActions(open = {}, enter = {}, toggle = {}),
                onClearFilters = {},
                bottomPadding = 0.dp,
                position = position,
                primaryAnchor = anchor,
            )
        }
        compose.waitForIdle()
    }

    private fun scrollTo(index: Int) {
        compose.onNodeWithTag(ListComposeTest.NOTE_LIST).performScrollToIndex(index)
        compose.waitForIdle()
        assertEquals(index, compose.runOnIdle { listState.firstVisibleItemIndex })
    }

    private fun assertTop() = compose.runOnIdle {
        assertEquals("first visible item", 0, listState.firstVisibleItemIndex)
        assertEquals("its offset", 0, listState.firstVisibleItemScrollOffset)
    }

    private companion object {
        const val SCROLLED = 12
        val ALICE = Primary.Entity(1, "Alice")
        val BOB = Primary.Entity(2, "Bob")
        val ALICE_NOTES = (1L..30L).toList()
        val BOB_NOTES = (101L..130L).toList()
        val TODAY: LocalDate = LocalDate.of(2026, 11, 17)

        /** Not-done rows of [ids], newest first (DEC-23). */
        fun rows(ids: List<Long>) = ids.map { row(it, checked = false) }

        fun row(id: Long, checked: Boolean) = NoteRowUi(
            id, "note $id", null, checked, null, overdue = false, emptyList(), emptyList(), orphan = true,
            orderedAt = orderedAt(id),
        )

        fun orderedAt(id: Long) = 1_000_000L - id

        /** A decided [primary] with its [rows] from read [rowsRead]. */
        fun stateOf(primary: Primary, rows: List<NoteRowUi>, rowsRead: Int = 0) = base.copy(
            primary = primary, rows = rows, rowsRead = rowsRead,
        )

        /** [primary] chosen, its rows not read yet (ui.md § 3.6). */
        fun loading(primary: Primary) = base.copy(loading = true, primary = primary, rows = emptyList())

        val base = NoteListUiState(
            spec = PerspectiveSpec.of(Perspective.Individual),
            loading = false,
            primary = ALICE,
            rows = emptyList(),
            rowsRead = 0,
            newNoteMarked = false,
            today = TODAY,
            chips = emptyList(),
            filtersActive = false,
            searchOpen = false,
            dateFilter = DateFilter.Any,
            selection = null,
            batchRunning = false,
            calendar = null,
            dialog = null,
            message = null,
        )
    }
}
