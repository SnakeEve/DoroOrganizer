package com.doro.organizer.ui.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.NewNoteMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stacks, Back, the tab counters and the visits of the editor and picker keys (architecture.md § Keys and stacks,
 * § Back behavior; ui.md § 2.1; DEC-100), and the binding of the new note's mark to the editor's return (testing.md
 * I-59 (b), DEC-110).
 */
class NavigatorTest {
    private val state = NavigationState(
        stacks = Perspective.entries.associateWith { mutableListOf<NavKey>(TabKey(it, epoch = 0)) },
        currentTab = mutableStateOf(Perspective.All),
        leaves = mutableStateOf(IntArray(Perspective.entries.size)),
        reselects = mutableStateOf(IntArray(Perspective.entries.size)),
        visits = mutableIntStateOf(0),
    )
    private val marker = MarkerCalls()
    private val navigator = Navigator(state, marker)

    /** A key that is not a tab root, as the editor's will be. */
    private data object Covering : NavKey

    @Test
    fun startsOnAll() {
        assertEquals(Perspective.All, state.current)
        assertEquals(TabKey(Perspective.All, 0), state.topKey)
    }

    @Test
    fun selectingAnotherTab_countsALeaveOfTheOldOne() {
        navigator.selectTab(Perspective.Project)

        assertEquals(Perspective.Project, state.current)
        assertEquals(1, state.leaveCount(Perspective.All))
        assertEquals(0, state.reselectCount(Perspective.Project))
    }

    @Test
    fun selectingTheCurrentTab_countsAReselectOnly() {
        navigator.selectTab(Perspective.All)

        assertEquals(Perspective.All, state.current)
        assertEquals(1, state.reselectCount(Perspective.All))
        assertEquals(0, state.leaveCount(Perspective.All))
    }

    @Test
    fun aCounterChange_replacesTheArray_soReadersRecompose() {
        val before = state.leaves.value

        navigator.selectTab(Perspective.Date)

        assertFalse(before === state.leaves.value)
        assertEquals(0, before[Perspective.All.ordinal])
    }

    @Test
    fun back_popsTheTopEntryFirst() {
        navigator.selectTab(Perspective.Orphan)
        navigator.open(Covering)

        navigator.back()

        assertEquals(Perspective.Orphan, state.current)
        assertEquals(TabKey(Perspective.Orphan, 0), state.topKey)
        assertEquals(0, state.leaveCount(Perspective.Orphan))
    }

    @Test
    fun back_fromAnotherTabsRoot_returnsToAll_keepingThatStack() {
        navigator.selectTab(Perspective.Individual)

        navigator.back()

        assertEquals(Perspective.All, state.current)
        assertEquals(1, state.leaveCount(Perspective.Individual))
        assertEquals(listOf(TabKey(Perspective.Individual, 0)), state.stacks.getValue(Perspective.Individual))
    }

    @Test
    fun back_atTheRootOfAll_changesNothing() {
        navigator.back()

        assertEquals(Perspective.All, state.current)
        assertEquals(listOf(TabKey(Perspective.All, 0)), state.stacks.getValue(Perspective.All))
        assertEquals(0, state.leaveCount(Perspective.All))
    }

    @Test
    fun open_pushesOntoTheCurrentStack_whoseTopIsNoLongerATabRoot() {
        navigator.selectTab(Perspective.Date)

        navigator.open(Covering)

        assertEquals(Covering, state.topKey)
        assertEquals(listOf(TabKey(Perspective.All, 0)), state.stacks.getValue(Perspective.All))
    }

    @Test
    fun aKeyEqualToTheTopApartFromItsVisit_isNotPushed_soADoubleTapOpensOneScreen() {
        val first = EditorKey(null, navigator.nextVisit())
        navigator.open(first)
        navigator.open(EditorKey(null, navigator.nextVisit()))

        assertEquals(listOf(TabKey(Perspective.All, 0), first), state.stacks.getValue(Perspective.All))
        navigator.back()
        assertEquals(TabKey(Perspective.All, 0), state.topKey)
    }

    @Test
    fun aPickerKeyEqualToTheTopApartFromItsVisit_isNotPushed() {
        val first = PickerKey(EntityKind.Person, PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null), navigator.nextVisit())
        navigator.open(first)
        navigator.open(PickerKey(EntityKind.Person, PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null), navigator.nextVisit()))

        assertEquals(listOf(TabKey(Perspective.All, 0), first), state.stacks.getValue(Perspective.All))
    }

    @Test
    fun theSameScreenOpenedAgain_isANewEntry_becauseItsVisitDiffers() {
        val leaving = EditorKey(null, navigator.nextVisit())
        navigator.open(leaving)
        navigator.back() // its exit animation may still run when + is tapped again
        val reopened = EditorKey(null, navigator.nextVisit())

        navigator.open(reopened)

        assertNotEquals(leaving, reopened)
        assertNotEquals(leaving.toString(), reopened.toString()) // the entry's contentKey
        assertEquals(listOf(TabKey(Perspective.All, 0), reopened), state.stacks.getValue(Perspective.All))
    }

    @Test
    fun aPickerOverTheEditor_isPushed() {
        navigator.open(EditorKey(7, navigator.nextVisit()))
        val picker = PickerKey(EntityKind.Person, PickerPurpose.EditorLinks(emptyList(), emptyList(), null, 0), navigator.nextVisit())

        navigator.open(picker)

        assertEquals(picker, state.topKey)
        assertEquals(3, state.stacks.getValue(Perspective.All).size)
    }

    @Test
    fun visits_countOnFromTheSavedCounter_inEveryTab() {
        val restored = NavigationState(
            stacks = state.stacks,
            currentTab = state.currentTab,
            leaves = state.leaves,
            reselects = state.reselects,
            visits = mutableIntStateOf(41),
        )
        val navigator = Navigator(restored, marker)

        assertEquals(42, navigator.nextVisit())
        navigator.selectTab(Perspective.Orphan)
        assertEquals(43, navigator.nextVisit())
        assertEquals(43, restored.visits.intValue)
    }

    @Test
    fun isTop_readsTheCurrentStacksTopEntry_atOnce() {
        val editor = EditorKey(7, navigator.nextVisit())
        navigator.open(editor)
        assertTrue(navigator.isTop(editor))
        assertFalse("the same screen of another visit", navigator.isTop(editor.copy(visit = editor.visit + 1)))

        navigator.open(Covering)
        assertFalse("covered", navigator.isTop(editor))
        navigator.back()
        assertTrue(navigator.isTop(editor))

        navigator.selectTab(Perspective.Orphan)
        assertFalse("another tab is current", navigator.isTop(editor))
        assertTrue(navigator.isTop(TabKey(Perspective.Orphan, 0)))
        navigator.selectTab(Perspective.All)
        navigator.back()
        assertFalse("popped", navigator.isTop(editor))
    }

    // The new note's mark (DEC-110).

    @Test
    fun back_poppingANewNotesEditor_expectsItsVisit() {
        val editor = EditorKey(null, navigator.nextVisit())
        navigator.open(editor)
        marker.calls.clear()

        navigator.back()

        assertEquals(listOf("expect(${editor.visit})"), marker.calls)
    }

    @Test
    fun back_poppingAnotherKey_expectsNothing() {
        navigator.open(EditorKey(7, navigator.nextVisit()))
        navigator.open(PickerKey(EntityKind.Person, PickerPurpose.EditorLinks(emptyList(), emptyList(), null, 0), navigator.nextVisit()))
        navigator.open(Covering)
        marker.calls.clear()

        repeat(3) { navigator.back() }

        assertEquals(emptyList<String>(), marker.calls)
    }

    @Test
    fun open_clearsTheMark_alsoForAKeyNotPushed() {
        val editor = EditorKey(null, navigator.nextVisit())
        navigator.open(editor)
        navigator.open(EditorKey(null, navigator.nextVisit()))

        assertEquals("not pushed (DEC-100)", listOf(TabKey(Perspective.All, 0), editor), state.stacks.getValue(Perspective.All))
        assertEquals(listOf("clear", "clear"), marker.calls)
    }

    @Test
    fun everyTabChange_clearsTheMark_andAReselectDoesNot() {
        navigator.selectTab(Perspective.All)
        assertEquals("a reselect", emptyList<String>(), marker.calls)

        navigator.selectTab(Perspective.Orphan)
        navigator.back() // from the root of Orphans to All

        assertEquals(listOf("clear", "clear"), marker.calls)
    }

    /** Records the calls [Navigator] makes. */
    private class MarkerCalls : NewNoteMarker {
        val calls = mutableListOf<String>()
        override val noteId: StateFlow<Long?> = MutableStateFlow(null)

        override fun expect(visit: Int) {
            calls += "expect($visit)"
        }

        override fun mark(noteId: Long, visit: Int) {
            calls += "mark"
        }

        override fun take(): Long? = null.also { calls += "take" }

        override fun clear() {
            calls += "clear"
        }
    }
}
