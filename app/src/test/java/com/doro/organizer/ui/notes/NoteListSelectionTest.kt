package com.doro.organizer.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.R
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.ui.DefaultNewNoteMarker
import com.doro.organizer.ui.navigation.PickerPurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Selection mode of `NoteListViewModel` (ui.md § 5, § 3.10; DEC-36, DEC-54) over scripted repositories on virtual
 * time: entering, toggling, pruning to each emission (testing.md I-42 (e)), every exit rule, the saved keys, and the
 * batch actions with their counts, failures and single-flight guard (I-21, I-22 ViewModel side). The rows of every
 * query are [rows], so a test changes the list by assigning it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteListSelectionTest {
    private val main = StandardTestDispatcher()
    private val time = FakeAppTime()
    private val today = time.today().toEpochDay()
    private val settings = FakeSettingsStore().apply { load() }
    private val notes = ScriptedNotes()
    private val entities = ScriptedEntities()
    private val store = ViewModelStore()
    private val rows = MutableStateFlow(listOf(noteRow(1), noteRow(2), noteRow(3)))
    private var created = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        notes.rows = { rows }
    }

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    // Entering, toggling and leaving (ui.md § 5.1, § 5.5 rules 1 and 3).

    @Test
    fun longPress_entersWithThatRow_tapsToggle_andDeselectingTheLastEnds() = runTest {
        val list = open(Perspective.All)
        assertNull(list.state.selection)

        list.vm.enterSelection(2)
        runCurrent()
        assertEquals(setOf(2L), list.state.selection)

        list.vm.toggleSelection(3)
        list.vm.toggleSelection(2)
        runCurrent()
        assertEquals(setOf(3L), list.state.selection)

        list.vm.toggleSelection(3)
        runCurrent()
        assertNull(list.state.selection)
        assertNull(list.handle[NoteListViewModel.KEY_SELECTION])
    }

    @Test
    fun exitSelection_clearsIt_andClosesItsDialog() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.openSetDate()
        runCurrent()
        assertEquals(ListDialog.SetDate, list.state.dialog)

        list.vm.exitSelection()
        runCurrent()

        assertNull(list.state.selection)
        assertNull(list.state.dialog)
        assertNull(list.handle[NoteListViewModel.KEY_LIST_DIALOG])
    }

    @Test
    fun theSelectionDialogs_openOnlyInSelectionMode() = runTest {
        val list = open(Perspective.All)
        list.vm.openSetDate()
        list.vm.openBatchDelete()
        runCurrent()
        assertNull(list.state.dialog)
    }

    @Test
    fun enteringAppliesAPendingSearchAtOnce_andARowTheSearchDropsEndsTheSelection() = runTest {
        // The searched list holds only note 1.
        notes.rows = { query -> rows.map { all -> all.filter { query.searchPattern == null || it.id == 1L } } }
        val list = open(Perspective.All)
        list.vm.openSearch()
        list.vm.onSearchTextChange("one")
        advanceTimeBy(100)

        list.vm.enterSelection(1)
        runCurrent()
        assertTrue("the debounce applied at once", notes.rowQueries.last().searchPattern != null)
        assertEquals(setOf(1L), list.state.selection)

        list.vm.exitSelection()
        list.vm.clearSearchText()
        runCurrent()
        list.vm.onSearchTextChange("one")
        advanceTimeBy(100)
        list.vm.enterSelection(2)
        runCurrent()
        assertNull("row 2 is not in the searched list", list.state.selection)
    }

    // Pruning (ui.md § 5.5 rule 4, testing.md I-42 (e)).

    @Test
    fun anEmissionWithoutASelectedRow_dropsIt_andWithoutAnyEndsTheSelectionAndItsDialog() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.toggleSelection(2)
        runCurrent()

        rows.value = listOf(noteRow(2), noteRow(3))
        runCurrent()
        assertEquals(setOf(2L), list.state.selection)
        assertArrayEquals(longArrayOf(2), list.handle[NoteListViewModel.KEY_SELECTION])

        list.vm.openBatchDelete()
        rows.value = listOf(noteRow(3))
        runCurrent()
        assertNull(list.state.selection)
        assertNull(list.state.dialog)
    }

    @Test
    fun aRestoredSelection_isPrunedToTheFirstEmission_andARestoredDialogWithoutOneIsDropped() = runTest {
        val restored = open(
            Perspective.All,
            handle(
                NoteListViewModel.KEY_SELECTION to longArrayOf(2, 9),
                NoteListViewModel.KEY_LIST_DIALOG to "SetDate",
            ),
        )
        assertEquals(setOf(2L), restored.state.selection)
        assertEquals(ListDialog.SetDate, restored.state.dialog)

        val gone = open(
            Perspective.All,
            handle(NoteListViewModel.KEY_SELECTION to longArrayOf(9), NoteListViewModel.KEY_LIST_DIALOG to "BatchDelete"),
        )
        assertNull(gone.state.selection)
        assertNull(gone.state.dialog)

        // Above 1,000 ids the selection was not saved, so its dialog comes back alone (ui.md § 3.10).
        val unsaved = open(Perspective.All, handle(NoteListViewModel.KEY_LIST_DIALOG to "SetDate"))
        assertNull(unsaved.state.dialog)
        assertNull(unsaved.handle[NoteListViewModel.KEY_LIST_DIALOG])
    }

    // Exit rules 5 and 6 (ui.md § 5.5).

    @Test
    fun leavingTheTab_endsTheSelection_onceForEachLeave() = runTest {
        val list = open(Perspective.All)
        list.vm.onTabLeft(0)
        list.vm.enterSelection(1)
        list.vm.onTabLeft(0) // rotation or a lock: the count did not grow
        runCurrent()
        assertEquals(setOf(1L), list.state.selection)

        list.vm.onTabLeft(1)
        runCurrent()
        assertNull(list.state.selection)
        assertEquals(1, list.handle[NoteListViewModel.KEY_HANDLED_LEAVES])

        list.vm.enterSelection(1)
        list.vm.onTabLeft(1)
        runCurrent()
        assertEquals(setOf(1L), list.state.selection)
    }

    @Test
    fun aHandledLeave_isRemembered_acrossAProcessDeath() = runTest {
        val list = open(
            Perspective.All,
            handle(NoteListViewModel.KEY_SELECTION to longArrayOf(1), NoteListViewModel.KEY_HANDLED_LEAVES to 3),
        )
        list.vm.onTabLeft(3)
        runCurrent()
        assertEquals(setOf(1L), list.state.selection)
    }

    @Test
    fun everyFilterChange_endsTheSelection() = runTest {
        val search: (NoteListViewModel) -> Unit = {
            it.openSearch()
            it.onSearchTextChange("x")
            it.submitSearch()
        }
        val changes = listOf(
            Change("person filter") { it.setEntityFilter(EntityKind.Person, ALICE) },
            Change("project filter") { it.setEntityFilter(EntityKind.Project, ALPHA) },
            Change("date filter") { it.selectDate(DateFilter.Undated) },
            Change("range end") { it.setRangeEnd(RangeEnd.From, today) },
            Change("cleared range end", setup = { it.setRangeEnd(RangeEnd.From, today) }) {
                it.clearRangeEnd(RangeEnd.From)
            },
            Change("search", change = search),
            Change("closed search", setup = search) { it.closeSearch() },
            Change("calendar range", Perspective.Date) { it.setRange(today, today + 2) },
            Change("cleared calendar range", Perspective.Date, setup = { it.setRange(today, today + 2) }) {
                it.clearRange()
            },
        )
        changes.forEach { (name, perspective, setup, change) ->
            val list = open(perspective)
            setup(list.vm)
            runCurrent()
            list.vm.enterSelection(1)
            change(list.vm)
            runCurrent()
            assertNull(name, list.state.selection)
        }
    }

    @Test
    fun aFilterClearedByTheExistenceProbe_endsTheSelection() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        val list = open(Perspective.All)
        list.vm.setEntityFilter(EntityKind.Person, ALICE)
        runCurrent()
        list.vm.enterSelection(1)
        runCurrent()

        entities.delete(EntityKind.Person, ALICE)
        runCurrent()

        assertNull(list.handle[NoteListViewModel.KEY_PERSON])
        assertNull(list.state.selection)
        assertFalse(list.handle.contains(NoteListViewModel.KEY_SELECTION))
    }

    @Test
    fun aFilterSetToItsCurrentValue_keepsTheSelection() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.setEntityFilter(EntityKind.Person, null)
        list.vm.clearFilters()
        runCurrent()
        assertEquals(setOf(1L), list.state.selection)
    }

    @Test
    fun anotherCalendarDay_endsTheSelection() = runTest {
        val list = open(Perspective.Date)
        list.vm.enterSelection(1)
        list.vm.showToday() // today is already selected: nothing changes
        runCurrent()
        assertEquals(setOf(1L), list.state.selection)

        list.vm.selectDay(today + 1)
        runCurrent()
        assertNull(list.state.selection)
    }

    @Test
    fun anotherOrNoPrimaryEntity_endsTheSelection_butARenameKeepsIt() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        list.vm.enterSelection(1)
        entities.put(EntityKind.Person, ALICE, "Alicia")
        runCurrent()
        assertEquals(Primary.Entity(ALICE, "Alicia"), list.state.primary)
        assertEquals(setOf(1L), list.state.selection)

        list.vm.choosePrimary(BOB)
        runCurrent()
        assertNull(list.state.selection)

        list.vm.enterSelection(1)
        entities.delete(EntityKind.Person, BOB)
        runCurrent()
        assertEquals(Primary.None, list.state.primary)
        assertNull(list.state.selection)
    }

    @Test
    fun aRestoredSelection_survivesTheFirstDecisionOfThePrimary() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual, handle(NoteListViewModel.KEY_SELECTION to longArrayOf(1, 2)))
        assertEquals(Primary.Entity(ALICE, "Alice"), list.state.primary)
        assertEquals(setOf(1L, 2L), list.state.selection)
    }

    @Test
    fun aRestoredSelection_endsWithItsDialog_whenTheRememberedEntityIsGone() = runTest {
        settings.setRemembered(EntityKind.Person, ALICE) // Alice no longer exists
        val list = open(
            Perspective.Individual,
            handle(NoteListViewModel.KEY_SELECTION to longArrayOf(1, 2), NoteListViewModel.KEY_LIST_DIALOG to "SetDate"),
        )

        assertEquals(Primary.None, list.state.primary)
        assertNull(list.state.selection)
        assertNull(list.state.dialog)
        assertFalse(list.handle.contains(NoteListViewModel.KEY_SELECTION))
    }

    // Saved state budget (ui.md § 3.10).

    @Test
    fun theSelection_isSavedUpTo1000Ids_andAbsentAboveOrOutsideSelectionMode() = runTest {
        rows.value = (1L..1_001L).map { noteRow(it) }
        notes.selectIdsResult = AppResult.Ok((1L..1_000L).toList())
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.selectAll()
        runCurrent()
        assertEquals(1_000, list.handle.get<LongArray>(NoteListViewModel.KEY_SELECTION)?.size)

        list.vm.toggleSelection(1_001)
        runCurrent()
        assertEquals(1_001, list.state.selection?.size)
        assertFalse(list.handle.contains(NoteListViewModel.KEY_SELECTION))

        list.vm.toggleSelection(1_001)
        runCurrent()
        assertEquals(1_000, list.handle.get<LongArray>(NoteListViewModel.KEY_SELECTION)?.size)

        list.vm.exitSelection()
        runCurrent()
        assertFalse(list.handle.contains(NoteListViewModel.KEY_SELECTION))
    }

    @Test
    fun theAssociatePicker_knowsTheSelectionIsSaved_exactlyUpTo1000Ids() = runTest {
        rows.value = (1L..1_001L).map { noteRow(it) }
        notes.selectIdsResult = AppResult.Ok((1L..1_000L).toList())
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.selectAll()
        runCurrent()
        assertEquals(1_000, list.state.selection?.size)
        assertEquals(PickerPurpose.BatchAssociate(selectionSaved = true), list.vm.associatePurpose())

        list.vm.toggleSelection(1_001)
        runCurrent()

        // DEC-57: a picker over this selection closes when restored, because its selection is not saved.
        assertEquals(1_001, list.state.selection?.size)
        assertFalse(list.handle.contains(NoteListViewModel.KEY_SELECTION))
        assertEquals(PickerPurpose.BatchAssociate(selectionSaved = false), list.vm.associatePurpose())
    }

    // Select all (ui.md § 5.3).

    @Test
    fun selectAll_replacesTheSelection_withTheIdsOfTheCurrentQuery() = runTest {
        notes.selectIdsResult = AppResult.Ok(listOf(3, 1, 2))
        val list = open(Perspective.All)
        list.vm.setRangeEnd(RangeEnd.Until, today)
        list.vm.openSearch()
        list.vm.onSearchTextChange("note")
        list.vm.submitSearch()
        runCurrent()
        list.vm.enterSelection(2)

        list.vm.selectAll()
        runCurrent()

        assertEquals(setOf(1L, 2L, 3L), list.state.selection)
        assertEquals(notes.rowQueries.last(), notes.selectIdsQueries.single())
        assertNull("Select all shows no snackbar", list.state.message)
    }

    @Test
    fun aFailedSelectAll_showsItsError_andKeepsTheSelection() = runTest {
        notes.selectIdsResult = AppResult.Err(AppError.DatabaseUnavailable)
        val list = open(Perspective.All)
        list.vm.enterSelection(2)

        list.vm.selectAll()
        runCurrent()

        assertEquals(setOf(2L), list.state.selection)
        assertEquals(UiText.Error(AppError.DatabaseUnavailable), list.state.message?.text)
        assertTrue(list.state.message!!.isError)
    }

    // Set date, delete and associate (ui.md § 5.3, § 8.3; DEC-36).

    @Test
    fun setDate_writesThePrunedSelection_endsSelectionMode_andCountsTheSelectedNotes() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.toggleSelection(2)
        list.vm.openSetDate()
        runCurrent()

        list.vm.setDateOfSelection(today)
        runCurrent()

        assertEquals(BatchCall.SetDate(setOf(1, 2), today), notes.batchCalls.single())
        assertNull(list.state.selection)
        assertNull(list.state.dialog)
        assertEquals(UiText.Plural(R.plurals.batch_date_set, 2), list.state.message?.text)
        assertFalse(list.state.message!!.isError)
    }

    @Test
    fun removeDate_setsNoDate_withItsOwnText() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(3)

        list.vm.setDateOfSelection(null)
        runCurrent()

        assertEquals(BatchCall.SetDate(setOf(3), null), notes.batchCalls.single())
        assertEquals(UiText.Plural(R.plurals.batch_date_removed, 1), list.state.message?.text)
    }

    @Test
    fun aSelectedNoteGoneMeanwhile_isPrunedBeforeTheCall() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.toggleSelection(3)
        runCurrent()
        rows.value = listOf(noteRow(1), noteRow(2))
        runCurrent()

        list.vm.deleteSelection()
        runCurrent()

        assertEquals(BatchCall.Delete(setOf(1)), notes.batchCalls.single())
        assertEquals(UiText.Plural(R.plurals.batch_deleted, 1), list.state.message?.text)
    }

    @Test
    fun aListChangedWhileItWasNotCollected_isPrunedBeforeTheCall() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.toggleSelection(2)
        runCurrent()
        // A full-screen picker covers the list: its collection stops, and a save on leave links note 1 meanwhile.
        list.collector.cancel()
        advanceTimeBy(5_001)
        rows.value = listOf(noteRow(2), noteRow(3))

        list.vm.associate(setOf(ALICE), emptySet(), emptyList(), emptyList())
        runCurrent()

        assertEquals(
            BatchCall.Associate(setOf(2), setOf(ALICE), emptySet(), emptyList(), emptyList()),
            notes.batchCalls.single(),
        )
    }

    @Test
    fun noSelectedNoteLeftInTheQuery_endsSelectionMode_withoutACall() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.openBatchDelete()
        runCurrent()
        notes.selectIdsResult = AppResult.Ok(listOf(2, 3))

        list.vm.deleteSelection()
        runCurrent()

        assertTrue(notes.batchCalls.isEmpty())
        assertNull(list.state.selection)
        assertNull(list.state.dialog)
        assertNull(list.state.message)
    }

    @Test
    fun anActionResult_leavesASelectionBegunAfterIt_alone() = runTest {
        val gate = CompletableDeferred<Unit>()
        notes.batchGate = gate
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.setDateOfSelection(today)
        runCurrent()
        list.vm.exitSelection()
        list.vm.enterSelection(2)
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertEquals(BatchCall.SetDate(setOf(1), today), notes.batchCalls.single())
        assertEquals(setOf(2L), list.state.selection)
        assertEquals(UiText.Plural(R.plurals.batch_date_set, 1), list.state.message?.text)
    }

    @Test
    fun aSelectAllResult_leavesASelectionBegunAfterIt_alone() = runTest {
        val gate = CompletableDeferred<Unit>()
        notes.selectIdsGate = gate
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.selectAll()
        runCurrent()
        list.vm.exitSelection()
        list.vm.enterSelection(2)

        gate.complete(Unit)
        runCurrent()

        assertEquals(setOf(2L), list.state.selection)
        assertFalse(list.state.batchRunning)
    }

    @Test
    fun associate_linksTheSelection_toTheChosenAndNewEntities() = runTest {
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.toggleSelection(2)
        val newProject = NewEntity("k1", "Gamma")

        list.vm.associate(setOf(ALICE), emptySet(), emptyList(), listOf(newProject))
        runCurrent()

        assertEquals(
            BatchCall.Associate(setOf(1, 2), setOf(ALICE), emptySet(), emptyList(), listOf(newProject)),
            notes.batchCalls.single(),
        )
        assertNull(list.state.selection)
        assertEquals(UiText.Plural(R.plurals.batch_associated, 2), list.state.message?.text)
    }

    @Test
    fun aFailedAction_showsItsError_closesItsDialog_andKeepsTheSelection() = runTest {
        notes.batchError = AppError.StorageFull
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.openBatchDelete()
        runCurrent()

        list.vm.deleteSelection()
        runCurrent()

        assertEquals(setOf(1L), list.state.selection)
        assertNull(list.state.dialog)
        assertEquals(UiText.Error(AppError.StorageFull), list.state.message?.text)
        assertTrue(list.state.message!!.isError)
    }

    @Test
    fun whileAnActionRuns_theBarIsDisabled_andASecondActionDoesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        notes.batchGate = gate
        val list = open(Perspective.All)
        list.vm.enterSelection(1)
        list.vm.openSetDate()

        list.vm.setDateOfSelection(today)
        runCurrent()
        assertTrue(list.state.batchRunning)
        assertEquals("the dialog stays while its action runs", ListDialog.SetDate, list.state.dialog)

        list.vm.setDateOfSelection(today)
        list.vm.deleteSelection()
        list.vm.selectAll()
        list.vm.openBatchDelete()
        runCurrent()
        assertEquals(1, notes.batchCalls.size)
        assertEquals("only the running action's own read", 1, notes.selectIdsQueries.size)
        assertEquals(ListDialog.SetDate, list.state.dialog)

        gate.complete(Unit)
        runCurrent()
        assertFalse(list.state.batchRunning)
        assertNull(list.state.selection)
    }

    @Test
    fun outsideSelectionMode_noActionRuns() = runTest {
        val list = open(Perspective.All)
        list.vm.selectAll()
        list.vm.setDateOfSelection(today)
        list.vm.deleteSelection()
        runCurrent()
        assertTrue(notes.batchCalls.isEmpty())
        assertTrue(notes.selectIdsQueries.isEmpty())
    }

    // Harness.

    private class Watched(val vm: NoteListViewModel, val handle: SavedStateHandle, val collector: Job) {
        val state: NoteListUiState get() = vm.uiState.value
    }

    /** One filter change of the exit-rule table: [setup] runs before selection mode starts, [change] in it. */
    private data class Change(
        val name: String,
        val perspective: Perspective = Perspective.All,
        val setup: (NoteListViewModel) -> Unit = {},
        val change: (NoteListViewModel) -> Unit,
    )

    /** [p]'s ViewModel over [handle], collected from now on, with its first emissions done. */
    private fun TestScope.open(p: Perspective, handle: SavedStateHandle = SavedStateHandle()): Watched {
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                initializer {
                    NoteListViewModel(
                        PerspectiveSpec.of(p), handle, notes, entities, settings, time, AppDispatchers(main, main, main),
                        DefaultNewNoteMarker(),
                    )
                }
            },
        )["list-${created++}", NoteListViewModel::class]
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        runCurrent()
        return Watched(vm, handle, collector)
    }

    private fun handle(vararg values: Pair<String, Any?>) = SavedStateHandle(mapOf(*values))

    private companion object {
        const val ALICE = 1L
        const val BOB = 2L
        const val ALPHA = 7L
    }
}
