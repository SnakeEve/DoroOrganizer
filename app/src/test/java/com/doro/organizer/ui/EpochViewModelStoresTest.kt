package com.doro.organizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ViewModels of the content per data epoch (architecture.md § Data reset): a reset clears the old epoch's. */
class EpochViewModelStoresTest {
    private val activity = ViewModelStore()
    private val stores = ViewModelProvider.create(
        activity,
        viewModelFactory { initializer { EpochViewModelStores() } },
    )[EpochViewModelStores::class]

    private class Entry : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private fun entryIn(store: ViewModelStore): Entry =
        ViewModelProvider.create(store, viewModelFactory { initializer { Entry() } })[Entry::class]

    @Test
    fun anEpoch_keepsItsStore() {
        assertSame(stores.storeOf(0), stores.storeOf(0))
        assertNotSame(stores.storeOf(0), stores.storeOf(1))
    }

    @Test
    fun aNewEpoch_clearsTheViewModelsOfEveryOtherEpoch_only() {
        val old = entryIn(stores.storeOf(0))
        val older = entryIn(stores.storeOf(1))
        val current = entryIn(stores.storeOf(2))

        stores.retainOnly(2)

        assertTrue(old.cleared)
        assertTrue(older.cleared)
        assertFalse(current.cleared)
        assertSame(current, entryIn(stores.storeOf(2)))
        assertNotSame("the old epoch starts from an empty store", old, entryIn(stores.storeOf(0)))
    }

    @Test
    fun theActivityFinishing_clearsEveryEpoch() {
        val entries = listOf(entryIn(stores.storeOf(0)), entryIn(stores.storeOf(1)))

        activity.clear()

        assertEquals(listOf(true, true), entries.map { it.cleared })
    }
}
