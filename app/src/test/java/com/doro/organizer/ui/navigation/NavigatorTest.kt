package com.doro.organizer.ui.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The stacks, Back, the tab counters and the visits of the editor and picker keys (architecture.md § Keys and stacks,
 * § Back behavior; ui.md § 2.1; DEC-100).
 */
class NavigatorTest {
    private val state = NavigationState(
        stacks = Perspective.entries.associateWith { mutableListOf<NavKey>(TabKey(it, epoch = 0)) },
        currentTab = mutableStateOf(Perspective.All),
        leaves = mutableStateOf(IntArray(Perspective.entries.size)),
        reselects = mutableStateOf(IntArray(Perspective.entries.size)),
        visits = mutableIntStateOf(0),
    )
    private val navigator = Navigator(state)

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
        val first = PickerKey(EntityKind.Person, PickerPurpose.BatchAssociate(selectionSaved = true), navigator.nextVisit())
        navigator.open(first)
        navigator.open(PickerKey(EntityKind.Person, PickerPurpose.BatchAssociate(selectionSaved = true), navigator.nextVisit()))

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
        val picker = PickerKey(EntityKind.Person, PickerPurpose.EditorLinks(emptyList()), navigator.nextVisit())

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
        val navigator = Navigator(restored)

        assertEquals(42, navigator.nextVisit())
        navigator.selectTab(Perspective.Orphan)
        assertEquals(43, navigator.nextVisit())
        assertEquals(43, restored.visits.intValue)
    }
}
