package com.doro.organizer.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.navigation3.runtime.NavKey
import com.doro.organizer.model.Perspective

/** The only writer of a [NavigationState] (architecture.md § Keys and stacks, § Back behavior). Main thread only. */
class Navigator(private val state: NavigationState) {
    /** A tab item tap: another tab becomes current, or the current one counts a reselect (ui.md § 2.1). */
    fun selectTab(p: Perspective) {
        val current = state.current
        if (p == current) {
            state.reselects.increment(p)
        } else {
            state.leaves.increment(current)
            state.currentTab.value = p
        }
    }

    /** The `visit` of the next [EditorKey] or [PickerKey] that an opener builds (DEC-100). */
    fun nextVisit(): Int = ++state.visits.intValue

    /**
     * Pushes [key] onto the current tab's stack. A key that equals the top entry apart from its `visit` is not
     * pushed: a double tap opens one screen (DEC-100).
     */
    fun open(key: NavKey) {
        val stack = state.stacks.getValue(state.current)
        if (stack.last().withoutVisit() != key.withoutVisit()) stack.add(key)
    }

    /**
     * Back that no screen consumed: pops the top entry, or from the root of a tab other than All returns to All with
     * that tab's stack kept. At the root of All `NavDisplay` has nothing to pop and the system handles Back.
     */
    fun back() {
        val stack = state.stacks.getValue(state.current)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            state.current != Perspective.All -> {
                state.leaves.increment(state.current)
                state.currentTab.value = Perspective.All
            }
        }
    }

    private fun NavKey.withoutVisit(): NavKey = when (this) {
        is EditorKey -> copy(visit = 0)
        is PickerKey -> copy(visit = 0)
        else -> this
    }

    private fun MutableState<IntArray>.increment(p: Perspective) {
        value = value.copyOf().also { it[p.ordinal]++ }
    }
}
