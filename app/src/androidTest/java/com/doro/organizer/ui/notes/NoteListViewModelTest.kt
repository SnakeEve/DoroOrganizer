package com.doro.organizer.ui.notes

import android.os.Parcel
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.ui.common.DaySelection
import java.time.YearMonth
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `NoteListViewModel` against the real repositories (architecture.md § Dependency injection, Tests): its
 * `SavedStateHandle` keys (ui.md § 3.10) survive a `Parcel` round trip and recreate equal state (testing.md I-38); a
 * deleted filter entity clears that dimension in every perspective that used it (I-42 (d)). The rules that need no
 * database are `NoteListViewModelRulesTest` on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class NoteListViewModelTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private val today = time.today().toEpochDay()
    private val settings = FakeSettingsStore().apply { load() }
    private val store = ViewModelStore()
    private lateinit var holder: DatabaseHolder
    private lateinit var rows: TestRows
    private lateinit var notes: NoteRepository
    private lateinit var entities: EntityRepository

    @Before
    fun createDatabase() {
        holder = databases.holder()
        rows = TestRows(holder.openDatabase().openHelper.writableDatabase)
        notes = noteRepository(holder, time, applicationScope)
        entities = entityRepository(holder, time, applicationScope)
    }

    @After
    fun clearViewModels() = runBlocking { onMain { store.clear() } }

    @Test
    fun savedState_survivesAParcelRoundTrip_andRecreatesEqualState() = runBlocking<Unit> {
        rows.note("alpha", createdAt = now, date = today)
        rows.note("beta", createdAt = now, date = today + 3)
        val all = SavedStateHandle()
        val date = SavedStateHandle()
        val allVm = viewModel(Perspective.All, all)
        val dateVm = viewModel(Perspective.Date, date)
        onMain {
            allVm.openSearch()
            allVm.onSearchTextChange("alpha")
            allVm.submitSearch()
            allVm.setRangeEnd(RangeEnd.From, today - 1)
            allVm.setRangeEnd(RangeEnd.Until, today + 1)
            allVm.openDateEndPicker(RangeEnd.Until)
            dateVm.setRange(today, today + 3)
            dateVm.showMonth(YearMonth.of(2027, 2))
            dateVm.openRangePicker()
        }
        val allState = allVm.settled()
        val dateState = dateVm.settled()

        val restoredAllVm = viewModel(Perspective.All, all.parcelled(), key = "restored-all")
        val restoredAll = restoredAllVm.settled()
        val restoredDate = viewModel(Perspective.Date, date.parcelled(), key = "restored-date").settled()

        assertEquals(allState, restoredAll)
        assertEquals(dateState, restoredDate)
        assertEquals(1, restoredAll.rows.size)
        assertEquals("alpha", restoredAllVm.searchText)
        assertEquals(ListDialog.DateEndPicker(RangeEnd.Until), restoredAll.dialog)
        assertEquals(ChipValue.Date(DateFilter.Range(today - 1, today + 1)), restoredAll.chips.last().value)
        assertEquals(DaySelection.Range(today, today + 3), restoredDate.calendar?.selection)
        assertEquals(YearMonth.of(2027, 2), restoredDate.calendar?.month)
        assertEquals(ListDialog.RangePicker, restoredDate.dialog)
    }

    @Test
    fun deletingTheFilterEntity_clearsTheDimension_inEveryPerspectiveThatUsedIt() = runBlocking<Unit> {
        val person = rows.person("Alice", now)
        rows.linkPerson(rows.note("linked", createdAt = now, date = today), person)
        val handles = listOf(Perspective.All, Perspective.Date, Perspective.Project).associateWith { SavedStateHandle() }
        val vms = handles.mapValues { (p, handle) -> viewModel(p, handle) }
        onMain { vms.values.forEach { it.setEntityFilter(EntityKind.Person, person) } }
        vms.values.forEach { vm -> vm.awaitState { chip(it, FilterDimension.Person) == ChipValue.Entity(person, "Alice") } }

        entities.deletePerson(person).ok()

        vms.forEach { (p, vm) ->
            val state = vm.awaitState { chip(it, FilterDimension.Person) == ChipValue.NotSet }
            assertTrue("$p", !state.filtersActive)
            assertNull("$p", handles.getValue(p).get<Long>(NoteListViewModel.KEY_PERSON))
        }
    }

    private fun viewModel(p: Perspective, handle: SavedStateHandle, key: String = p.name): NoteListViewModel {
        val vm = NoteListViewModel(PerspectiveSpec.of(p), handle, notes, entities, settings, time, AppDispatchers.production())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        return ViewModelProvider.create(store, factory)[key, NoteListViewModel::class]
    }

    /** The state once the list has loaded and no debounce is pending. */
    private suspend fun NoteListViewModel.settled(): NoteListUiState = awaitState { !it.loading }

    private suspend fun NoteListViewModel.awaitState(predicate: (NoteListUiState) -> Boolean): NoteListUiState =
        withTimeout(TIMEOUT) { uiState.first(predicate) }

    private fun chip(state: NoteListUiState, dimension: FilterDimension): ChipValue? =
        state.chips.firstOrNull { it.dimension == dimension }?.value

    /** This handle's values as a process death keeps them: written to a `Parcel` and read back. */
    private fun SavedStateHandle.parcelled(): SavedStateHandle {
        val bundle = bundleOf(*keys().map { it to get<Any?>(it) }.toTypedArray())
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            val restored = checkNotNull(parcel.readBundle(javaClass.classLoader))
            @Suppress("DEPRECATION") // the values' types differ per key
            return SavedStateHandle(restored.keySet().associateWith { restored.get(it) })
        } finally {
            parcel.recycle()
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private companion object {
        val TIMEOUT = 10.seconds
    }
}
