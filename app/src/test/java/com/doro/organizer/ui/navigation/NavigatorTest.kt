package com.doro.organizer.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import com.doro.organizer.model.Perspective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The stacks, Back and the tab counters (architecture.md § Keys and stacks, § Back behavior; ui.md § 2.1). */
class NavigatorTest {
    private val state = NavigationState(
        stacks = Perspective.entries.associateWith { mutableListOf<NavKey>(TabKey(it, epoch = 0)) },
        currentTab = mutableStateOf(Perspective.All),
        leaves = mutableStateOf(IntArray(Perspective.entries.size)),
        reselects = mutableStateOf(IntArray(Perspective.entries.size)),
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
}
