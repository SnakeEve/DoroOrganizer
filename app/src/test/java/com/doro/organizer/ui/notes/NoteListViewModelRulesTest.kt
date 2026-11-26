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
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.EpochDays
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.SearchText
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.ui.DefaultNewNoteMarker
import com.doro.organizer.ui.common.DaySelection
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rules of `NoteListViewModel` that need no database (ui.md § 3.3–3.6, § 4.2, § 4.4.2), over scripted
 * repositories on virtual time: the queries it asks for, its `uiState` and its `SavedStateHandle` keys. The
 * instrumented `NoteListViewModelTest` covers the real repositories and the `Parcel` round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModelRulesTest {
    private val main = StandardTestDispatcher()
    private val time = FakeAppTime()
    private val today = time.today().toEpochDay()
    private val settings = FakeSettingsStore().apply { load() }
    private val notes = ScriptedNotes()
    private val entities = ScriptedEntities()
    private val marker = DefaultNewNoteMarker()
    private val store = ViewModelStore()
    private var created = 0

    @Before
    fun mainDispatcher() = Dispatchers.setMain(main)

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    // Date filter (ui.md § 3.5).

    @Test
    fun anyDateOrUndated_clearsBothRangeEnds() = runTest {
        val list = open(Perspective.All)
        list.vm.setRangeEnd(RangeEnd.From, today - 3)
        list.vm.setRangeEnd(RangeEnd.Until, today + 3)
        runCurrent()
        assertEquals(DateFilter.Range(today - 3, today + 3), list.state.dateFilter)

        list.vm.selectDate(DateFilter.Undated)
        runCurrent()

        assertEquals(DateFilter.Undated, list.state.dateFilter)
        assertEquals("Undated", list.handle[NoteListViewModel.KEY_DATE_KIND])
        assertNull(list.handle[NoteListViewModel.KEY_DATE_FROM])
        assertNull(list.handle[NoteListViewModel.KEY_DATE_TO])
        val query = notes.rowQueries.last()
        assertTrue(query.undatedOnly)
        assertNull(query.fromDay)
        assertNull(query.toDay)

        list.vm.selectDate(DateFilter.Any)
        runCurrent()
        assertEquals(DateFilter.Any, list.state.dateFilter)
        assertFalse(notes.rowQueries.last().undatedOnly)
    }

    @Test
    fun dateRange_withoutARange_opensTheFromPicker_whoseCancelKeepsThePreviousChoice() = runTest {
        val list = open(Perspective.All)
        list.vm.openDateFilter()
        list.vm.selectDate(DateFilter.Undated)

        list.vm.selectDateRange()
        runCurrent()
        assertEquals(ListDialog.DateEndPicker(RangeEnd.From), list.state.dialog)

        list.vm.dismissDateEndPicker()
        runCurrent()
        assertEquals(ListDialog.DateFilterSheet, list.state.dialog)
        assertEquals(DateFilter.Undated, list.state.dateFilter)

        list.vm.setRangeEnd(RangeEnd.Until, today)
        list.vm.selectDateRange() // a range is already set: the radio opens nothing
        runCurrent()
        assertEquals(ListDialog.DateFilterSheet, list.state.dialog)
        assertEquals(DateFilter.Range(null, today), list.state.dateFilter)
    }

    @Test
    fun clearingOneEnd_keepsAnOpenRange_andClearingTheLast_returnsToAnyDate() = runTest {
        val list = open(Perspective.Orphan)
        list.vm.setRangeEnd(RangeEnd.From, today)
        list.vm.setRangeEnd(RangeEnd.Until, today + 1)

        list.vm.clearRangeEnd(RangeEnd.Until)
        runCurrent()
        assertEquals(DateFilter.Range(today, null), list.state.dateFilter)
        assertEquals(today, notes.rowQueries.last().fromDay)
        assertEquals("an open end is the last day, never undated notes", EpochDays.MAX, notes.rowQueries.last().toDay)

        list.vm.clearRangeEnd(RangeEnd.From)
        runCurrent()
        assertEquals(DateFilter.Any, list.state.dateFilter)
        assertEquals("Any", list.handle[NoteListViewModel.KEY_DATE_KIND])
        assertEquals(ChipValue.NotSet, list.state.chips.single().value)
    }

    // Calendar selection (ui.md § 4.4.2).

    @Test
    fun rangePickerOk_withOneDay_givesDayMode_onTheMonthOfThatDay() = runTest {
        val list = open(Perspective.Date)
        val day = LocalDate.of(2027, 3, 9).toEpochDay()

        list.vm.setRange(day, day)
        runCurrent()

        val calendar = checkNotNull(list.state.calendar)
        assertEquals(DaySelection.Day(day), calendar.selection)
        assertEquals(YearMonth.of(2027, 3), calendar.month)
        assertEquals(Primary.Day(day), list.state.primary)
        assertEquals(day, notes.rowQueries.last().fromDay)
        assertEquals(day, notes.rowQueries.last().toDay)
    }

    @Test
    fun rangePickerOk_withTwoDays_givesRangeMode_onTheMonthOfTheStart_andClearRangeKeepsTheMonth() = runTest {
        val list = open(Perspective.Date)
        val start = LocalDate.of(2026, 12, 30).toEpochDay()

        list.vm.setRange(start + 4, start)
        runCurrent()
        assertEquals(DaySelection.Range(start, start + 4), list.state.calendar?.selection)
        assertEquals(YearMonth.of(2026, 12), list.state.calendar?.month)
        assertEquals(start, list.handle[NoteListViewModel.KEY_RANGE_FROM])
        assertEquals(start + 4, list.handle[NoteListViewModel.KEY_RANGE_TO])

        list.vm.showMonth(YearMonth.of(2027, 1))
        list.vm.clearRange()
        runCurrent()
        assertEquals(DaySelection.Day(start), list.state.calendar?.selection)
        assertEquals(YearMonth.of(2027, 1), list.state.calendar?.month)
        assertNull(list.handle[NoteListViewModel.KEY_RANGE_FROM])
    }

    // Search (ui.md § 3.3).

    @Test
    fun searchText_isStoredAtOnce_andFiltersAfterThe300msDebounce() = runTest {
        val list = open(Perspective.All)
        list.vm.openSearch()

        list.vm.onSearchTextChange("alp")
        assertEquals("alp", list.vm.searchText) // before any dispatch: the field never waits
        assertEquals("alp", list.handle[NoteListViewModel.KEY_SEARCH_TEXT])

        advanceTimeBy(299)
        runCurrent()
        assertNull(notes.rowQueries.last().searchPattern)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("alp", notes.rowQueries.last().searchPattern)
        assertTrue(list.state.filtersActive)
    }

    @Test
    fun submitClearAndClose_applyAtOnce_andClosingDropsTextAndFilter() = runTest {
        val list = open(Perspective.All)
        list.vm.openSearch()
        list.vm.onSearchTextChange("beta")
        list.vm.submitSearch()
        runCurrent()
        assertEquals("beta", notes.rowQueries.last().searchPattern)

        list.vm.clearSearchText()
        runCurrent()
        assertEquals("", list.vm.searchText)
        assertNull(notes.rowQueries.last().searchPattern)
        assertTrue("the bar stays open", list.state.searchOpen)

        list.vm.onSearchTextChange("gamma")
        list.vm.submitSearch()
        list.vm.closeSearch()
        runCurrent()
        assertFalse(list.state.searchOpen)
        assertEquals("", list.vm.searchText)
        assertEquals(false, list.handle[NoteListViewModel.KEY_SEARCH_OPEN])
        assertNull(notes.rowQueries.last().searchPattern)
        assertFalse(list.state.filtersActive)
    }

    @Test
    fun savedText_filtersOnlyWhileTheBarIsOpen() = runTest {
        val closed = open(Perspective.All, handle(NoteListViewModel.KEY_SEARCH_TEXT to "kept"))
        assertNull(notes.rowQueries.last().searchPattern)
        assertFalse(closed.state.searchOpen)

        open(
            Perspective.All,
            handle(NoteListViewModel.KEY_SEARCH_OPEN to true, NoteListViewModel.KEY_SEARCH_TEXT to "kept"),
        )
        assertEquals("kept", notes.rowQueries.last().searchPattern)
    }

    @Test
    fun searchInput_isCappedAtTheTermLimit_keepingSurrogatePairsWhole() = runTest {
        val list = open(Perspective.All)
        list.vm.openSearch()

        list.vm.onSearchTextChange("a".repeat(SearchText.MAX_TERM_CHARS + 5))
        assertEquals(SearchText.MAX_TERM_CHARS, list.vm.searchText.length)

        list.vm.onSearchTextChange("a".repeat(SearchText.MAX_TERM_CHARS - 1) + "😀")
        assertEquals("a".repeat(SearchText.MAX_TERM_CHARS - 1), list.vm.searchText)
    }

    // Filters and the primary (ui.md § 3.6, § 4.2).

    @Test
    fun filtersActive_countsSecondaryFiltersAndSearch_andClearFilters_neverTouchesThePrimary() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Project, ALPHA, "Alpha")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        assertEquals(Primary.Entity(ALICE, "Alice"), list.state.primary)
        assertFalse("the primary never counts", list.state.filtersActive)

        list.vm.setEntityFilter(EntityKind.Project, ALPHA)
        list.vm.selectDate(DateFilter.Undated)
        list.vm.openSearch()
        list.vm.onSearchTextChange("x")
        list.vm.submitSearch()
        runCurrent()
        assertTrue(list.state.filtersActive)
        assertEquals(ChipValue.Entity(ALPHA, "Alpha"), list.state.chips.first().value)

        list.vm.clearFilters()
        runCurrent()
        assertFalse(list.state.filtersActive)
        assertFalse(list.state.searchOpen)
        assertEquals(Primary.Entity(ALICE, "Alice"), list.state.primary)
        assertEquals(ALICE, settings.snapshot().rememberedPersonId)
        assertEquals(NoteQuery(ALICE, null, false, false, null, null, null), notes.rowQueries.last())
    }

    @Test
    fun aNewPrimary_clearsTheRows_underItsOwnTitle_andNeverFallsBackToLoading() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        val bobsRows = CompletableDeferred<Unit>()
        notes.rows = { query ->
            when (query.personId) {
                ALICE -> flowOf(listOf(noteRow(1)))
                else -> flow {
                    bobsRows.await()
                    emit(listOf(noteRow(2)))
                }
            }
        }
        val list = open(Perspective.Individual)
        assertEquals(listOf(1L), list.state.rows.map { it.id })
        val fromChoice = list.states.size

        list.vm.choosePrimary(BOB)
        runCurrent()

        // Bob's query has not answered: his title, no rows of Alice, loading.
        assertEquals(Primary.Entity(BOB, "Bob"), list.state.primary)
        assertTrue(list.state.loading)
        assertTrue(list.state.rows.isEmpty())

        bobsRows.complete(Unit)
        runCurrent()
        assertEquals(listOf(2L), list.state.rows.map { it.id })
        val afterChoice = list.states.drop(fromChoice)
        assertTrue(afterChoice.none { it.primary == Primary.Loading })
        assertTrue(afterChoice.none { state -> state.primary == Primary.Entity(BOB, "Bob") && state.rows.any { it.id == 1L } })
    }

    // DEC-99 as the owner signed it off: a choice that cannot be saved opens its notes anyway.

    @Test
    fun aFailedChoice_opensItsNotesAnyway_saysItIsNotRemembered_andRemembersNothing() = runTest {
        entities.put(EntityKind.Project, ALPHA, "Alpha")
        val list = open(Perspective.Project)
        assertEquals(Primary.None, list.state.primary)
        settings.failEdits = true

        list.vm.choosePrimary(ALPHA)
        runCurrent()

        assertEquals(UiText.Res(R.string.primary_not_remembered), list.state.message?.text)
        assertTrue(list.state.message?.isError == true)
        assertEquals(Primary.Entity(ALPHA, "Alpha"), list.state.primary)
        assertEquals(ALPHA, notes.rowQueries.last().projectId)
        assertNull(settings.snapshot().rememberedProjectId)
    }

    @Test
    fun anUnsavedChoice_givesWayToALaterSavedOne_evenOfTheRememberedEntity() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        settings.failEdits = true
        list.vm.choosePrimary(BOB)
        runCurrent()
        assertEquals(Primary.Entity(BOB, "Bob"), list.state.primary)

        settings.failEdits = false
        list.vm.choosePrimary(ALICE)
        runCurrent()

        assertEquals(Primary.Entity(ALICE, "Alice"), list.state.primary)
        assertEquals(ALICE, settings.snapshot().rememberedPersonId)
        assertEquals(Primary.Entity(ALICE, "Alice"), open(Perspective.Individual, restoredFrom(list.handle)).state.primary)
    }

    @Test
    fun anUnsavedChoice_survivesAProcessDeath_butNotTheNextStart() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        settings.failEdits = true
        list.vm.choosePrimary(BOB)
        runCurrent()
        settings.failEdits = false

        val restored = open(Perspective.Individual, restoredFrom(list.handle))
        val nextStart = open(Perspective.Individual)

        // DEC-99 (6): a restored screen looks the same; the settings were never changed.
        assertEquals(Primary.Entity(BOB, "Bob"), restored.state.primary)
        assertEquals(Primary.Entity(ALICE, "Alice"), nextStart.state.primary)
    }

    @Test
    fun anUnsavedChoiceWhoseEntityIsDeleted_showsThePicker_notTheOlderRememberedOne() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        settings.failEdits = true
        list.vm.choosePrimary(BOB)
        runCurrent()

        entities.delete(EntityKind.Person, BOB)
        runCurrent()

        assertEquals(Primary.None, list.state.primary)
        assertEquals("the remembered id is not Bob's, so it stays", ALICE, settings.snapshot().rememberedPersonId)
        assertEquals(Primary.None, open(Perspective.Individual, restoredFrom(list.handle)).state.primary)
    }

    @Test
    fun aChoiceMadeWhileTheDeletedEntityIsForgotten_isKept() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        settings.setRemembered(EntityKind.Project, ALPHA)
        val list = open(Perspective.Individual)

        // The picker body appears as soon as the probe reports Alice gone; the user picks Bob in the same instant.
        entities.delete(EntityKind.Person, ALICE)
        list.vm.choosePrimary(BOB)
        runCurrent()

        assertEquals(BOB, settings.snapshot().rememberedPersonId)
        assertEquals(ALPHA, settings.snapshot().rememberedProjectId)
        assertEquals(Primary.Entity(BOB, "Bob"), list.state.primary)
    }

    // "All people" / "All projects" (DEC-112).

    @Test
    fun clearPrimary_showsTheBody_andForgetsTheRememberedIdAndAnUnsavedChoice_inOneEdit_alsoForTheNextStart() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        settings.setRemembered(EntityKind.Project, ALPHA)
        val list = open(Perspective.Individual)
        settings.failEdits = true
        list.vm.choosePrimary(BOB)
        runCurrent()
        settings.failEdits = false
        val edits = settings.edits

        list.vm.clearPrimary()
        runCurrent()

        assertEquals(Primary.None, list.state.primary)
        assertEquals("one edit", edits + 1, settings.edits)
        assertNull("Alice, behind the unsaved choice of Bob, is gone too", settings.snapshot().rememberedPersonId)
        assertEquals("the other perspective's choice stays", ALPHA, settings.snapshot().rememberedProjectId)
        assertNull(list.handle.get<Long>(NoteListViewModel.KEY_UNSAVED_CHOICE))
        assertEquals("a restored screen", Primary.None, open(Perspective.Individual, restoredFrom(list.handle)).state.primary)
        assertEquals("the next start", Primary.None, open(Perspective.Individual).state.primary)
    }

    @Test
    fun clearPrimary_dropsTheUnsavedChoice_onlyOnceTheSettingsShowTheClear_soTheOlderIdNeverShows() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        settings.failEdits = true
        list.vm.choosePrimary(BOB)
        runCurrent()
        settings.failEdits = false
        settings.publishEdits = false
        val fromClear = list.states.size

        list.vm.clearPrimary()
        runCurrent()
        assertEquals("the unsaved choice until the settings show the clear", Primary.Entity(BOB, "Bob"), list.state.primary)
        settings.load()
        runCurrent()

        assertEquals(Primary.None, list.state.primary)
        val shownAfter = list.states.drop(fromClear).map { it.primary }
        assertFalse("Alice shown: $shownAfter", shownAfter.any { it is Primary.Entity && it.id == ALICE })
    }

    @Test
    fun clearPrimary_keepsTheSecondaryFilters_forTheNextEntity() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Person, BOB, "Bob")
        entities.put(EntityKind.Project, ALPHA, "Alpha")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        list.vm.setEntityFilter(EntityKind.Project, ALPHA)
        runCurrent()

        list.vm.clearPrimary()
        runCurrent()
        assertEquals(Primary.None, list.state.primary)
        list.vm.choosePrimary(BOB)
        runCurrent()

        assertEquals(Primary.Entity(BOB, "Bob"), list.state.primary)
        assertEquals(BOB, notes.rowQueries.last().personId)
        assertEquals(ALPHA, notes.rowQueries.last().projectId)
    }

    @Test
    fun aFailedClear_showsTheBodyAnyway_saysItIsNotRemembered_andTheNextStartShowsTheEntity() = runTest {
        entities.put(EntityKind.Project, ALPHA, "Alpha")
        settings.setRemembered(EntityKind.Project, ALPHA)
        val list = open(Perspective.Project)
        settings.failEdits = true

        list.vm.clearPrimary()
        runCurrent()

        assertEquals(Primary.None, list.state.primary)
        assertEquals(UiText.Res(R.string.primary_not_remembered), list.state.message?.text)
        assertTrue(list.state.message?.isError == true)
        assertEquals(ALPHA, settings.snapshot().rememberedProjectId)
        assertEquals("a restored screen", Primary.None, open(Perspective.Project, restoredFrom(list.handle)).state.primary)
        settings.failEdits = false
        assertEquals("the next start", Primary.Entity(ALPHA, "Alpha"), open(Perspective.Project).state.primary)
    }

    @Test
    fun thePickerBody_closesTheListSearch() = runTest {
        entities.put(EntityKind.Person, ALICE, "Alice")
        settings.setRemembered(EntityKind.Person, ALICE)
        val list = open(Perspective.Individual)
        list.vm.openSearch()
        list.vm.onSearchTextChange("x")
        runCurrent()

        entities.delete(EntityKind.Person, ALICE)
        runCurrent()

        assertEquals(Primary.None, list.state.primary)
        assertFalse(list.state.searchOpen)
        assertEquals("", list.vm.searchText)
        assertNull(settings.snapshot().rememberedPersonId)
    }

    @Test
    fun beforeTheFirstRows_theBarChipsAndDialogsAlreadyRespond() = runTest {
        notes.rows = { emptyFlow() } // DatabaseHolder.observe while the database is not open
        val list = open(Perspective.All)

        list.vm.openSearch()
        list.vm.openDateFilter()
        runCurrent()

        assertTrue(list.state.loading)
        assertTrue(list.state.searchOpen)
        assertEquals(ListDialog.DateFilterSheet, list.state.dialog)
    }

    @Test
    fun theFirstState_holdsTheSavedChipsAndTheCalendar_beforeAnyQuery() {
        val all = viewModel(
            Perspective.All,
            handle(
                NoteListViewModel.KEY_DATE_KIND to "Range",
                NoteListViewModel.KEY_DATE_FROM to today,
                NoteListViewModel.KEY_PERSON to ALICE,
            ),
        ).uiState.value
        assertEquals(DateFilter.Range(today, null), all.dateFilter)
        assertEquals(
            listOf(ChipValue.NotSet, ChipValue.NotSet, ChipValue.Date(DateFilter.Range(today, null))),
            all.chips.map { it.value },
        )
        assertTrue(all.filtersActive)

        val date = viewModel(Perspective.Date, handle(NoteListViewModel.KEY_DAY to today + 2)).uiState.value
        assertEquals(CalendarUi(YearMonth.from(time.today()), time.today(), DaySelection.Day(today + 2), emptySet()), date.calendar)
        assertTrue(notes.rowQueries.isEmpty())
    }

    // Calendar marks (ui.md § 4.4.1, DEC-53, testing.md I-32).

    @Test
    fun marks_followThePersonAndProjectFilters_andIgnoreTheSelectedDay() = runTest {
        val p1 = EntityRef(ALICE, "Alice")
        val p2 = EntityRef(BOB, "Bob")
        val project = EntityRef(ALPHA, "Alpha")
        entities.put(EntityKind.Person, ALICE, "Alice")
        entities.put(EntityKind.Project, ALPHA, "Alpha")
        val dated = listOf(
            noteRow(1, date = today, persons = listOf(p1)),
            noteRow(2, date = today + 1, persons = listOf(p2), projects = listOf(project)),
        )
        notes.marks = { query, month ->
            dated.filter { row ->
                row.date!! in month &&
                    (query.personId == null || row.persons.any { it.id == query.personId }) &&
                    (query.projectId == null || row.projects.any { it.id == query.projectId })
            }.mapNotNull { it.date }.toSet()
        }
        val list = open(Perspective.Date)
        assertEquals(setOf(today, today + 1), list.state.calendar?.markedDays)

        list.vm.setEntityFilter(EntityKind.Person, ALICE)
        runCurrent()
        assertEquals(setOf(today), list.state.calendar?.markedDays)

        list.vm.setEntityFilter(EntityKind.Person, null)
        list.vm.setEntityFilter(EntityKind.Project, ALPHA)
        runCurrent()
        assertEquals(setOf(today + 1), list.state.calendar?.markedDays)

        val markRuns = notes.markQueries.size
        list.vm.selectDay(today + 1)
        runCurrent()
        assertEquals("a day tap does not re-run the marks", markRuns, notes.markQueries.size)
        assertEquals(today + 1, notes.rowQueries.last().fromDay)
    }

    // Done toggle (ui.md § 3.6, § 3.9).

    @Test
    fun setChecked_reportsTheChange_andAFailureShowsItsText() = runTest {
        val list = open(Perspective.All)
        val results = mutableListOf<Boolean>()

        list.vm.setChecked(1, checked = true) { results += it }
        runCurrent()
        notes.setCheckedResult = AppResult.Err(AppError.DatabaseUnavailable)
        list.vm.setChecked(1, checked = false) { results += it }
        runCurrent()

        assertEquals(listOf(true, false), results)
        assertEquals(UiText.Error(AppError.DatabaseUnavailable), list.state.message?.text)
    }

    // The mark of a note just created and the kept row (ui.md § 3.11, DEC-110; testing.md I-59 (f)).

    @Test
    fun takeNewNote_clearsTheMark_readsTheSameQueryOnceMore_andWaitsForThatRead() = runTest {
        var reads = 0
        notes.rows = { flowOf(if (reads++ == 0) listOf(noteRow(1)) else listOf(noteRow(9), noteRow(1))) }
        val list = open(Perspective.All)
        marker.expect(3)
        marker.mark(noteId = 9, visit = 3)
        runCurrent()
        assertTrue(list.state.newNoteMarked)
        val asked = notes.rowQueries.toList()

        val wait = list.vm.takeNewNote()
        runCurrent()

        assertEquals(NewNoteWait(noteId = 9, read = 1), wait)
        assertNull(marker.noteId.value)
        assertFalse(list.state.newNoteMarked)
        assertEquals(asked + asked.last(), notes.rowQueries)
        assertEquals(listOf(9L, 1L), list.state.rows.map { it.id })
        assertEquals(1, list.state.rowsRead)
        val earlier = list.states.filter { state -> state.rows.map { it.id } == listOf(1L) }
        assertTrue(earlier.isNotEmpty())
        assertTrue("rows of the earlier read carry its number", earlier.all { it.rowsRead == 0 })
    }

    @Test
    fun takeNewNote_onThePickerBody_dropsTheMark_andReadsNothing() = runTest {
        val list = open(Perspective.Individual)
        assertEquals(Primary.None, list.state.primary)
        marker.expect(3)
        marker.mark(noteId = 9, visit = 3)
        runCurrent()

        assertNull(list.vm.takeNewNote())
        runCurrent()

        assertNull(marker.noteId.value)
        assertEquals(emptyList<NoteQuery>(), notes.rowQueries)
    }

    @Test
    fun takeNewNote_withoutAMark_returnsNull_andReadsNothingMore() = runTest {
        notes.rows = { flowOf(listOf(noteRow(1))) }
        val list = open(Perspective.All)
        val asked = notes.rowQueries.size

        assertNull(list.vm.takeNewNote())
        runCurrent()

        assertEquals(asked, notes.rowQueries.size)
        assertEquals(0, list.state.rowsRead)
    }

    @Test
    fun aKeptRow_isTakenOnce() = runTest {
        val list = open(Perspective.All)
        val row = KeptRow(4, checked = false, orderedAt = 40)

        list.vm.keepRow(row)

        assertEquals(row, list.vm.takeKeptRow())
        assertNull(list.vm.takeKeptRow())
    }

    /** A collected ViewModel and every state it emitted. */
    private class Watched(val vm: NoteListViewModel, val handle: SavedStateHandle, val states: List<NoteListUiState>) {
        val state: NoteListUiState get() = vm.uiState.value
    }

    /** [p]'s ViewModel over [handle], collected from now on, with its first emissions done. */
    private fun TestScope.open(p: Perspective, handle: SavedStateHandle = SavedStateHandle()): Watched {
        val vm = viewModel(p, handle)
        val states = mutableListOf<NoteListUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { states += it } }
        runCurrent()
        return Watched(vm, handle, states)
    }

    private fun viewModel(p: Perspective, handle: SavedStateHandle): NoteListViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer {
                NoteListViewModel(
                    PerspectiveSpec.of(p), handle, notes, entities, settings, time, AppDispatchers(main, main, main), marker,
                )
            }
        },
    )["list-${created++}", NoteListViewModel::class]

    private fun handle(vararg values: Pair<String, Any?>) = SavedStateHandle(mapOf(*values))

    /** The handle a ViewModel rebuilt after a process death gets: every value [handle] saved. */
    private fun restoredFrom(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private companion object {
        const val ALICE = 1L
        const val BOB = 2L
        const val ALPHA = 7L
    }
}
