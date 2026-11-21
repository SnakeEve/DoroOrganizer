package com.doro.organizer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** The key of the saved content state of [epoch] in the `DataEpochScope`'s holder. */
internal fun contentStateKey(epoch: Int) = "content-epoch-$epoch"

/**
 * The ViewModel stores of the content, one per data epoch, in the activity's store: the NavEntry ViewModels of an
 * epoch live in its store and are cleared with it.
 */
internal class EpochViewModelStores : ViewModel() {
    private val stores = mutableMapOf<Int, ViewModelStore>()

    fun storeOf(epoch: Int): ViewModelStore = stores.getOrPut(epoch) { ViewModelStore() }

    /** Clears the stores of every other epoch, so their ViewModels' `onCleared` runs. */
    fun retainOnly(epoch: Int) {
        (stores.keys - epoch).forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

/**
 * Where the content keeps its state, per data epoch (architecture.md § Data reset): the saveable state under
 * [contentStateKey] and the ViewModels in the epoch's store. It lives above the gate branch, so a data reset drops
 * the old epoch's state whether or not the content was composed when the epoch changed: a Restore under the overlay,
 * an Erase or Start fresh behind the gate screens, or startup reconciliation after a process death.
 */
@Stable
internal class DataEpochScope(
    private val holder: SaveableStateHolder,
    private val stores: EpochViewModelStores,
    private val parent: HasDefaultViewModelProviderFactory,
) {
    /** [content] with the saved state and the ViewModel store of [epoch]; a new epoch starts from nothing. */
    @Composable
    fun Content(epoch: Int, content: @Composable () -> Unit) {
        val owner = remember(epoch) { EpochOwner(stores.storeOf(epoch), parent) }
        holder.SaveableStateProvider(contentStateKey(epoch)) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
        }
    }
}

/**
 * The [DataEpochScope] of the activity. When [dataEpoch] changes it removes the old epoch's saved state and clears
 * its ViewModels, then runs [onReset]; the last epoch is saveable, so a reset that happened while the process was dead
 * is seen too. Nothing happens while [dataEpoch] is null (the settings are not read yet).
 */
@Composable
internal fun rememberDataEpochScope(dataEpoch: Int?, onReset: () -> Unit): DataEpochScope {
    val holder = rememberSaveableStateHolder()
    val parent = checkNotNull(LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory) {
        "the activity owns the epoch stores"
    }
    val stores = viewModel { EpochViewModelStores() }
    var lastEpoch by rememberSaveable { mutableStateOf<Int?>(null) }
    val currentOnReset by rememberUpdatedState(onReset)
    // After the composition that moved the content to the new key: the old provider has left and saved its state,
    // which removeState then drops (a provider still composed would only stop saving).
    SideEffect {
        val last = lastEpoch
        if (dataEpoch != null && dataEpoch != last) {
            lastEpoch = dataEpoch
            stores.retainOnly(dataEpoch)
            if (last != null) {
                holder.removeState(contentStateKey(last))
                currentOnReset()
            }
        }
    }
    return remember(holder, stores, parent) { DataEpochScope(holder, stores, parent) }
}

/** The epoch's store with the activity's factory and extras, so entry ViewModels are built as before. */
private class EpochOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory get() = parent.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras get() = parent.defaultViewModelCreationExtras
}
