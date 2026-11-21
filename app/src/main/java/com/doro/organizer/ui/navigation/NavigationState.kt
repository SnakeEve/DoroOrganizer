package com.doro.organizer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.doro.organizer.model.Perspective

/** True only inside the entry that is on top of the current stack: the one that draws the shell's snackbar (ui.md § 2.4). */
val LocalIsTopEntry = compositionLocalOf { false }

/**
 * One back stack per tab, the current tab, the tab counters (architecture.md § Keys and stacks, ui.md § 2.1) and the
 * last `visit` given to an editor or picker key (DEC-100). Each counter array is indexed by `Perspective.ordinal` and
 * replaced, never mutated, so that readers recompose. Only [Navigator] writes it.
 */
@Stable
class NavigationState(
    internal val stacks: Map<Perspective, MutableList<NavKey>>,
    internal val currentTab: MutableState<Perspective>,
    internal val leaves: MutableState<IntArray>,
    internal val reselects: MutableState<IntArray>,
    internal val visits: MutableIntState,
) {
    val current: Perspective get() = currentTab.value

    /** The entry on top of the current tab's stack. */
    val topKey: NavKey get() = stacks.getValue(current).last()

    /** How often the user left tab [p] (ui.md § 2.1): its list clears the selection when this grows. */
    fun leaveCount(p: Perspective): Int = leaves.value[p.ordinal]

    /** How often tab [p] was tapped while current (ui.md § 2.1): its list scrolls to the top when this grows. */
    fun reselectCount(p: Perspective): Int = reselects.value[p.ordinal]

    /**
     * The entries `NavDisplay` shows: the All stack, followed by the current tab's stack when that is not All.
     * Every stack is decorated on every composition, so a tab that is not shown keeps its entries' saved state and
     * ViewModels (DEC-50, DEC-28).
     */
    @Composable
    fun rememberDisplayedEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        val withTopFlag = { key: NavKey ->
            val entry = entryProvider(key)
            NavEntry<NavKey>(navEntry = entry) {
                CompositionLocalProvider(LocalIsTopEntry provides (key == topKey)) { entry.Content() }
            }
        }
        val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { "the data epoch owns the entry stores" }
        val decorated = Perspective.entries.associateWith { p ->
            // Not rememberViewModelStoreProvider: it clears every entry's ViewModel when this composable leaves
            // composition while the activity lives, which every lock does. This provider keeps its stores in the
            // epoch's store under a fixed key, so unlocking finds the same ViewModels (architecture.md § Invariants 3,
            // § Keys and stacks item (d)); they are cleared when their key is popped and with their data epoch.
            val stores = remember(viewModelStoreOwner) {
                ViewModelStoreProvider(viewModelStoreOwner, "entries-${p.name}")
            }
            val decorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>(stores),
            )
            rememberDecoratedNavEntries(stacks.getValue(p), decorators, withTopFlag)
        }
        val all = decorated.getValue(Perspective.All)
        return if (current == Perspective.All) all else all + decorated.getValue(current)
    }
}

/**
 * The saved navigation of the shell for [dataEpoch]: one root per tab on All at first. It is saved in the content
 * state of that epoch, which a data reset drops as a whole (`DataEpochScope`), so its stacks never hold an id of
 * replaced data (architecture.md § Data reset).
 */
@Composable
fun rememberNavigationState(dataEpoch: Int): NavigationState {
    val stacks = Perspective.entries.associateWith { rememberNavBackStack(TabKey(it, dataEpoch)) }
    val current = rememberSaveable { mutableStateOf(Perspective.All) }
    val leaves = rememberSaveable { mutableStateOf(IntArray(Perspective.entries.size)) }
    val reselects = rememberSaveable { mutableStateOf(IntArray(Perspective.entries.size)) }
    // Saved with the stacks, so a key restored after process death is never given out again (DEC-100).
    val visits = rememberSaveable { mutableIntStateOf(0) }
    return remember { NavigationState(stacks, current, leaves, reselects, visits) }
}
