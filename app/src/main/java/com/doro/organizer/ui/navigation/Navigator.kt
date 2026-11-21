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

    /** Pushes [key] onto the current tab's stack. */
    fun open(key: NavKey) {
        state.stacks.getValue(state.current).add(key)
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

    private fun MutableState<IntArray>.increment(p: Perspective) {
        value = value.copyOf().also { it[p.ordinal]++ }
    }
}
