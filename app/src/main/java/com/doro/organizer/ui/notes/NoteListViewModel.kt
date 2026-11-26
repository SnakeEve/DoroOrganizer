package com.doro.organizer.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doro.organizer.R
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.SearchText
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.model.capChars
import com.doro.organizer.model.toQuery
import com.doro.organizer.settings.Settings
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.NewNoteMarker
import com.doro.organizer.ui.common.DaySelection
import com.doro.organizer.ui.common.NoteRowUi
import com.doro.organizer.ui.common.toRowUi
import com.doro.organizer.ui.navigation.PickerPurpose
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Typing pauses this long before the search text filters the list (ui.md § 3.3). */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * The largest selection the `SavedStateHandle` holds (ui.md § 3.10): the activity state stays below 500 KB, so a
 * larger selection survives rotation in the ViewModel but not process death.
 */
private const val MAX_SAVED_SELECTION = 1_000

/**
 * The state holder of one perspective's list (ui.md § 3, § 4), one per tab. Filters, search and the Date selection
 * live in [savedStateHandle] (DEC-28, keys of ui.md § 3.10); the Individual and Project primary is the remembered id
 * of [settingsStore], checked by an existence probe (data-layer.md § Reactivity). The rows come only from the
 * database: a write never patches the state (architecture.md § Layering rule 3). Selection mode and its batch actions
 * (ui.md § 5) are a state of this ViewModel, never a navigation entry. Every input of [uiState] emits at
 * once, starting from what is shown when it restarts, so the search bar, the chips and the dialogs respond before the
 * first rows arrive and a returning tab shows its last list on the first frame (ui.md § 3.6). The mark of a note just
 * created ([newNoteMarker]) is taken here, and the row a list showed first is kept here across a return, both in
 * memory only (ui.md § 3.11, DEC-110).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModel(
    val spec: PerspectiveSpec,
    private val savedStateHandle: SavedStateHandle,
    private val noteRepository: NoteRepository,
    private val entityRepository: EntityRepository,
    private val settingsStore: SettingsStore,
    private val appTime: AppTime,
    private val dispatchers: AppDispatchers,
    private val newNoteMarker: NewNoteMarker,
) : ViewModel() {
    private val personFilter = MutableStateFlow(savedStateHandle.get<Long>(KEY_PERSON))
    private val projectFilter = MutableStateFlow(savedStateHandle.get<Long>(KEY_PROJECT))
    private val dateFilter = MutableStateFlow(savedDateFilter())
    private val searchOpen = MutableStateFlow(savedStateHandle[KEY_SEARCH_OPEN] ?: false)

    /**
     * The search field's text (ui.md § 3.3). Snapshot state, written inside the field's `onValueChange`, so the field
     * shows every edit in the frame it happens instead of after a flow round trip; [searchTextFlow] carries the same
     * value to the flows.
     */
    var searchText by mutableStateOf(savedStateHandle[KEY_SEARCH_TEXT] ?: "")
        private set
    private val searchTextFlow = MutableStateFlow(searchText)

    /** The search text the query uses: [searchText] after the debounce, and only while the bar is open. */
    private val appliedSearch = MutableStateFlow(if (searchOpen.value) searchText else "")
    private var debounce: Job? = null

    private val daySelection = MutableStateFlow(savedDaySelection())
    private val month = MutableStateFlow(savedMonth())
    private val savedDialog = savedListDialogOf(savedStateHandle[KEY_LIST_DIALOG])
    private val dialog = MutableStateFlow((savedDialog as? SavedListDialog.Shown)?.dialog)

    /**
     * The rename dialog's text (ui.md § 8.17, DEC-113), saved under `renameText` while [ListDialog.Rename] is open and
     * removed with it. Snapshot state, like [searchText], so the field shows every edit in the frame it happens.
     */
    var renameText by mutableStateOf(savedStateHandle[KEY_RENAME_TEXT] ?: "")
        private set

    /** The impact read of "Delete…" still running: one tap opens one dialog; a dialog change ends it (DEC-113). */
    private var deleteRead: Job? = null
    private val message = MutableStateFlow<UserMessage?>(null)

    /** The selected note ids; null outside selection mode (ui.md § 5.1). */
    private val selection = MutableStateFlow(savedStateHandle.get<LongArray>(KEY_SELECTION)?.toSet())
    private val batchRunning = MutableStateFlow(false)

    /**
     * The last `leaveCount` of the tab this ViewModel handled (ui.md § 2.1). Snapshot state, so the screen hides the
     * selection of a leave not handled yet from its first frame on (§ 5.5 rule 5).
     */
    var handledLeaves by mutableIntStateOf(savedStateHandle[KEY_HANDLED_LEAVES] ?: 0)
        private set

    /**
     * Grows whenever selection mode starts or ends, so the result of an action that outlived its selection never
     * changes a later one (ui.md § 5.3).
     */
    private var selectionSession = 0L

    /** The Individual or Project entity last shown, whose replacement ends selection mode (ui.md § 5.5 rule 6). */
    private var shownEntity: Long? = null

    /**
     * A choice of the primary entity that could not be saved in the settings (DEC-99), shown in place of the remembered
     * id. It is kept with the screen's saved state, so a restored screen still shows it, but never in the settings, so
     * the next start shows the remembered one.
     */
    private val unsavedChoice = MutableStateFlow(UnsavedChoice.of(savedStateHandle[KEY_UNSAVED_CHOICE]))

    private val primary: Flow<Primary> = when {
        spec.primaryKind != null -> entityPrimary(spec.primaryKind)
        spec.showsMonthGrid -> daySelection.map { it.toPrimary() }
        else -> flowOf(Primary.None)
    }

    private val personName = entityFilterName(EntityKind.Person, FilterDimension.Person, personFilter)
    private val projectName = entityFilterName(EntityKind.Project, FilterDimension.Project, projectFilter)

    /**
     * The query of every filter, and the filter of the primary alone, which tells whether rows belong to the current
     * primary. Equal queries never re-run SQL (data-layer.md § Perspective to NoteQuery mapping, contract item 1).
     * Shared, so the rows, the marks and the state run one existence probe.
     */
    private val listQuery: Flow<ListQuery> =
        combine(primary, personFilter, projectFilter, dateFilter, appliedSearch) { primary, person, project, date, search ->
            val filter = NoteFilter(
                perspective = spec.perspective,
                primaryId = (primary as? Primary.Entity)?.id,
                primaryDays = primary.days(),
                personId = person,
                projectId = project,
                date = date,
                search = search,
            )
            val primaryOnly = filter.copy(personId = null, projectId = null, date = DateFilter.Any, search = "")
            ListQuery(primary, primaryOnly, filter.toQuery())
        }
            .distinctUntilChanged()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    /** The last rows [results] produced, so a restarted collection starts from them. */
    private var lastResult: RowsResult? = null

    /** The number of the current read of the list query: 0, then one more for each [takeNewNote] (DEC-110 (2)). */
    private val reread = MutableStateFlow(0)

    /** The row first on screen when the list left composition (DEC-110 (3)); in memory only. */
    private var keptRow: KeptRow? = null

    /**
     * The mapped rows, the primary they belong to and the read that produced them; null while no list query runs (the
     * picker body) or answered. Each emission prunes the selection to its rows (ui.md § 5.5 rule 4).
     */
    private val results: Flow<RowsResult?> = combine(listQuery, reread, ::Pair)
        .flatMapLatest { (list, read) -> list.query?.let { rowsOf(list, it, read) } ?: flowOf(null) }
        .onStart { emit(lastResult) }
        .onEach { result ->
            lastResult = result
            result?.let { pruneSelection(it.rows.mapTo(HashSet(it.rows.size), NoteRowUi::id)) }
        }

    private val listPart: Flow<ListPart> = combine(listQuery, results, appTime.todayFlow) { list, result, today ->
        val current = result?.takeIf { it.primaryKey == list.primaryKey }
        ListPart(
            loading = when (list.primary) {
                Primary.Loading -> true
                Primary.None -> list.query != null && current == null
                else -> current == null
            },
            primary = list.primary,
            rows = current?.rows.orEmpty(),
            rowsRead = current?.read ?: 0,
            today = today,
        )
    }

    private val filterPart: Flow<FilterPart> = combine(
        combine(personFilter, personName, projectFilter, projectName, ::EntityFilters),
        dateFilter,
        searchOpen,
        searchTextFlow,
    ) { entities, date, open, text ->
        FilterPart(
            chips = chipsOf(entities, date),
            filtersActive = filtersActive(entities.person, entities.project, date, text),
            searchOpen = open,
            dateFilter = date,
        )
    }

    private val calendar: Flow<CalendarUi?> = if (!spec.showsMonthGrid) {
        flowOf(null)
    } else {
        // The marks replace the selection's bounds by the month's (data-layer.md § Perspective query item 6), so a
        // day tap or a new range does not re-run them.
        val marksQuery = listQuery.map { it.query?.copy(fromDay = null, toDay = null) }.distinctUntilChanged()
        val marks = combine(marksQuery, month, ::Pair)
            .flatMapLatest { (query, shown) -> query?.let { markedDays(it, shown) } ?: flowOf(emptySet()) }
            .onStart { emit(uiState.value.calendar?.markedDays.orEmpty()) }
        combine(month, appTime.todayFlow, daySelection, marks, ::CalendarUi)
    }

    private val screenPart: Flow<ScreenPart> = combine(
        dialog,
        message,
        selection,
        batchRunning,
        newNoteMarker.noteId.map { it != null }.distinctUntilChanged(),
        ::ScreenPart,
    )

    val uiState: StateFlow<NoteListUiState> =
        combine(listPart, filterPart, calendar, screenPart) { list, filters, calendar, screen ->
            NoteListUiState(
                spec = spec,
                loading = list.loading,
                primary = list.primary,
                rows = list.rows,
                rowsRead = list.rowsRead,
                newNoteMarked = screen.newNoteMarked,
                today = list.today,
                chips = filters.chips,
                filtersActive = filters.filtersActive,
                searchOpen = filters.searchOpen,
                dateFilter = filters.dateFilter,
                selection = screen.selection,
                batchRunning = screen.batchRunning,
                calendar = calendar,
                dialog = screen.dialog,
                message = screen.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        // A fresh Date entry opens on the current month with today selected (DEC-53); the values are saved at once,
        // so a later process death restores this selection even after midnight.
        if (spec.showsMonthGrid) {
            saveDaySelection(daySelection.value)
            savedStateHandle[KEY_MONTH] = month.value.atDay(1).toEpochDay()
        }
        // A selection too large to be saved is gone after a process death, and its dialog with it (ui.md § 3.10).
        if (selection.value == null && dialog.value.needsSelection()) setDialog(null)
        // A restored delete dialog keeps only its id: it reads its counts again before it shows (DEC-113 (6)). Only
        // Individual and Project show an entity, so only they keep a dialog of one.
        if (spec.primaryKind == null && dialog.value.entityId() != null) setDialog(null)
        (savedDialog as? SavedListDialog.DeleteEntity)?.let { restored ->
            setDialog(null)
            if (spec.primaryKind != null) openDelete(restored.id)
        }
    }

    // Search (ui.md § 3.3).

    fun openSearch() = setSearchOpen(true)

    /** Stores the text at once, capped without a message; the list follows after the debounce. */
    fun onSearchTextChange(text: String) {
        val capped = capChars(text, SearchText.MAX_TERM_CHARS)
        storeSearchText(capped)
        debounce?.cancel()
        debounce = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            applySearch(capped)
        }
    }

    /** The keyboard's search action applies the text at once. */
    fun submitSearch() = applySearchNow(searchText)

    /** The clear button empties the field and the filter at once; the bar stays open. */
    fun clearSearchText() {
        storeSearchText("")
        applySearchNow("")
    }

    /**
     * The close button and Back close the bar and drop its text and filter (ui.md § 2.5); so does the return of the
     * picker body, whose Search action belongs to the picker's own field (ui.md § 4.2).
     */
    fun closeSearch() {
        setSearchOpen(false)
        clearSearchText()
    }

    // Secondary filters (ui.md § 3.4, § 3.5).

    /**
     * Sets or, with null, clears the [kind] filter: the result of the chip's picker (`PickerResult.Filter`, ui.md
     * § 7.7). At most one person and one project (DEC-28).
     */
    fun setEntityFilter(kind: EntityKind, id: Long?) {
        val filter = if (kind == EntityKind.Person) personFilter else projectFilter
        if (filter.value != id) endSelection()
        setSaved(filter, if (kind == EntityKind.Person) KEY_PERSON else KEY_PROJECT, id)
    }

    /** "Clear filters" of the empty state (ui.md § 3.6): every secondary filter and the search; never the primary. */
    fun clearFilters() {
        setEntityFilter(EntityKind.Person, null)
        setEntityFilter(EntityKind.Project, null)
        setDateFilter(DateFilter.Any)
        closeSearch()
    }

    fun openDateFilter() = setDialog(ListDialog.DateFilterSheet)

    /** "Any date" or "Undated", which clear both range ends (ui.md § 3.5 rule 2). */
    fun selectDate(filter: DateFilter) {
        require(filter !is DateFilter.Range) { "a range is set end by end" }
        setDateFilter(filter)
    }

    /** The "Date range" radio: with no range yet it opens the From picker, whose cancel keeps the old choice (rule 4). */
    fun selectDateRange() {
        if (dateFilter.value !is DateFilter.Range) setDialog(ListDialog.DateEndPicker(RangeEnd.From))
    }

    fun openDateEndPicker(end: RangeEnd) = setDialog(ListDialog.DateEndPicker(end))

    /** OK of an end's picker: sets that end, selects "Date range" and returns to the sheet (rule 3). */
    fun setRangeEnd(end: RangeEnd, epochDay: Long) {
        val range = dateFilter.value as? DateFilter.Range ?: DateFilter.Range(null, null)
        setDateFilter(if (end == RangeEnd.From) range.copy(fromDay = epochDay) else range.copy(toDay = epochDay))
        setDialog(ListDialog.DateFilterSheet)
    }

    /** Clearing one end keeps an open-ended range; clearing the last one returns to "Any date" (rule 5, DEC-28). */
    fun clearRangeEnd(end: RangeEnd) {
        val range = dateFilter.value as? DateFilter.Range ?: return
        val rest = if (end == RangeEnd.From) range.copy(fromDay = null) else range.copy(toDay = null)
        setDateFilter(if (rest.fromDay == null && rest.toDay == null) DateFilter.Any else rest)
    }

    /** Cancel or Back on an end's picker returns to the sheet unchanged. */
    fun dismissDateEndPicker() = setDialog(ListDialog.DateFilterSheet)

    /** Closes the open sheet or dialog. */
    fun closeDialog() = setDialog(null)

    // Primary selection (ui.md § 4.2, § 4.4).

    /**
     * Remembers [id] as the person of Individual or the project of Project (DEC-28), from the embedded picker or the
     * title's picker; secondary filters and search stay. A choice that cannot be saved is shown anyway, but not
     * remembered for the next start, and a message says so (DEC-99).
     */
    fun choosePrimary(id: Long) = rememberEntity(id, ifNotSaved = UnsavedChoice.Entity(id))

    /**
     * "All people" / "All projects" of the title's picker (DEC-112): forgets the remembered entity of this perspective,
     * whatever id it holds, and an unsaved choice, so the picker body shows now and at every later start. The other
     * perspective's choice and the secondary and date filters stay (DEC-112 (5)); `Primary.None` closes the list search
     * as for any return of the picker body. When that cannot be saved the picker body shows anyway, for the life of this
     * saved state, and a message says the choice is not remembered (DEC-112 (3)).
     */
    fun clearPrimary() = rememberEntity(null, ifNotSaved = UnsavedChoice.Cleared)

    /**
     * Writes [id] (null: none) as this perspective's remembered entity and drops an unsaved choice only once the
     * settings show the write, so an older remembered id never shows in between (DEC-112 (2)). A failed write shows
     * [ifNotSaved] instead, with the message of DEC-99.
     */
    private fun rememberEntity(id: Long?, ifNotSaved: UnsavedChoice) {
        val kind = checkNotNull(spec.primaryKind) { "only Individual and Project have an entity" }
        viewModelScope.launch {
            try {
                if (id == null) settingsStore.clearRemembered(kind) else settingsStore.setRemembered(kind, id)
                settingsStore.settings.first { it != null && it.rememberedId(kind) == id }
                setUnsavedChoice(UnsavedChoice.None)
            } catch (_: IOException) {
                // A failed DataStore write is a full disk (SettingsStore).
                setUnsavedChoice(ifNotSaved)
                message.value = UserMessage.of(UiText.Res(R.string.primary_not_remembered), isError = true)
            }
        }
    }

    /** A day tap of `MonthGrid`: day mode on that day, also from range mode (ui.md § 4.4.1). */
    fun selectDay(epochDay: Long) = setDaySelection(DaySelection.Day(epochDay))

    /** A swipe or an arrow of `MonthGrid`. */
    fun showMonth(shown: YearMonth) = setMonth(shown)

    /** "Today": the current month, today selected in day mode. */
    fun showToday() {
        val today = appTime.today()
        setMonth(YearMonth.from(today))
        setDaySelection(DaySelection.Day(today.toEpochDay()))
    }

    fun openRangePicker() = setDialog(ListDialog.RangePicker)

    /**
     * OK of the range picker (ui.md § 4.4.2): start = end gives day mode on that day, otherwise range mode. Either
     * way the grid shows the month of the start, so the selected day is never off screen.
     */
    fun setRange(fromDay: Long, toDay: Long) {
        setDialog(null)
        val start = minOf(fromDay, toDay)
        val end = maxOf(fromDay, toDay)
        setDaySelection(if (start == end) DaySelection.Day(start) else DaySelection.Range(start, end))
        setMonth(YearMonth.from(LocalDate.ofEpochDay(start)))
    }

    /** "Clear range": day mode on the range's first day; the displayed month stays. */
    fun clearRange() {
        (daySelection.value as? DaySelection.Range)?.let { setDaySelection(DaySelection.Day(it.fromDay)) }
    }

    // Rows (ui.md § 3.9).

    /**
     * Sets the note's done state to [checked], never a toggle (DEC-22), in [viewModelScope], so a failure shows its
     * snackbar even after the screen left (ui.md § 3.6). [onDone] receives whether the row changed: the screen
     * announces a change and otherwise drops its scroll anchor (ui.md § 3.9 items 4 and 5).
     */
    fun setChecked(noteId: Long, checked: Boolean, onDone: (changed: Boolean) -> Unit) {
        viewModelScope.launch {
            val changed = when (val result = noteRepository.setChecked(noteId, checked)) {
                is AppResult.Ok -> result.value
                is AppResult.Err -> {
                    message.value = UserMessage.error(result.error)
                    false
                }
            }
            onDone(changed)
        }
    }

    // Selection mode (ui.md § 5).

    /**
     * A long-press on a row outside selection mode: a pending search debounce applies at once, then the row is
     * selected, so the search filter cannot end the selection a moment after it began (ui.md § 5.1). If the row is
     * missing from the next emission, pruning ends selection mode again.
     */
    fun enterSelection(noteId: Long) {
        if (selection.value != null) return
        if (debounce?.isActive == true) applySearchNow(searchText)
        setSelection(setOf(noteId))
    }

    /** A row tap, or a long-press, in selection mode; deselecting the last row ends it (ui.md § 5.5 rule 3). */
    fun toggleSelection(noteId: Long) {
        val current = selection.value ?: return
        setSelection((if (noteId in current) current - noteId else current + noteId).ifEmpty { null })
    }

    /** "Exit selection" and Back (ui.md § 5.5 rule 1). */
    fun exitSelection() = endSelection()

    /**
     * The tab was left [leaveCount] times in all (ui.md § 2.1): a count this ViewModel has not handled yet ends
     * selection mode (§ 5.5 rule 5). Rotation, a lock or a covering picker never raise the count.
     */
    fun onTabLeft(leaveCount: Int) {
        if (leaveCount <= handledLeaves) return
        handledLeaves = leaveCount
        savedStateHandle[KEY_HANDLED_LEAVES] = leaveCount
        endSelection()
    }

    /**
     * "Select all" (ui.md § 5.3): the ids of every row of the current query, checked and off-screen notes included,
     * replace the selection; selection mode stays. A selection ended or begun anew meanwhile is left alone.
     */
    fun selectAll() {
        if (selection.value == null || batchRunning.value) return
        batchRunning.value = true
        val session = selectionSession
        viewModelScope.launch {
            try {
                when (val result = currentIds()) {
                    is AppResult.Ok -> if (session == selectionSession) setSelection(result.value.ifEmpty { null })
                    is AppResult.Err -> message.value = UserMessage.error(result.error)
                }
            } finally {
                batchRunning.value = false
            }
        }
    }

    /** "Set date…" of the contextual bar's menu. */
    fun openSetDate() = openSelectionDialog(ListDialog.SetDate)

    /** OK of the batch date picker with the picked day, or "Remove date" with null (ui.md § 5.3, DEC-36). */
    fun setDateOfSelection(epochDay: Long?) = runBatch({ noteRepository.setDate(it, epochDay) }) {
        UiText.Plural(if (epochDay == null) R.plurals.batch_date_removed else R.plurals.batch_date_set, it.selected)
    }

    /** "Delete" of the contextual bar's menu: the confirmation of ui.md § 8.3. */
    fun openBatchDelete() = openSelectionDialog(ListDialog.BatchDelete)

    /** "Delete" of the batch delete dialog: a hard delete of every selected note (DEC-35, spec §1). */
    fun deleteSelection() = runBatch(noteRepository::deleteNotes) { UiText.Plural(R.plurals.batch_deleted, it.selected) }

    /**
     * The purpose of the Associate picker over the current selection, which a process death keeps exactly when its
     * saved key is present (DEC-57, [setSelection]). In the Project perspective it names the chosen project, whose
     * people the People tab lists first (DEC-111).
     */
    fun associatePurpose() = PickerPurpose.BatchAssociate(
        selectionSaved = savedStateHandle.contains(KEY_SELECTION),
        coLinkedTo = (uiState.value.primary as? Primary.Entity)?.id?.takeIf { spec.primaryKind == EntityKind.Project },
    )

    /**
     * The result of the batch associate picker (ui.md § 5.3, § 7.7): links the selection to the chosen and the new
     * persons and projects in one additive transaction (DEC-33, DEC-36).
     */
    fun associate(personIds: Set<Long>, projectIds: Set<Long>, newPersons: List<NewEntity>, newProjects: List<NewEntity>) =
        runBatch({ noteRepository.associate(it, personIds, projectIds, newPersons, newProjects) }) {
            UiText.Plural(R.plurals.batch_associated, it.selected)
        }

    // Rename and delete of the shown person or project (ui.md § 4.2, § 8.4, § 8.17; DEC-113).

    /**
     * "Rename…" of the overflow menu: the dialog of the shown entity, holding its name cut at `MAX_NAME_CHARS` (a
     * restored backup can hold a longer one, DEC-113 (2)).
     */
    fun openRename() {
        val shown = uiState.value.primary as? Primary.Entity ?: return
        storeRenameText(capChars(shown.name, MAX_NAME_CHARS))
        setDialog(ListDialog.Rename(shown.id))
    }

    /** The rename field's text, capped at `MAX_NAME_CHARS` without a message, a surrogate pair kept whole. */
    fun onRenameTextChange(text: String) {
        if (dialog.value is ListDialog.Rename) storeRenameText(capChars(text, MAX_NAME_CHARS))
    }

    /**
     * "Rename" of the dialog of entity [id]: closes it at once, then writes the text, which the repository trims. Only
     * while that dialog is open, so a second confirm before it is gone writes nothing (DEC-113 (4)), and only while the
     * text is not blank, as the button says (DEC-113 (2)). A written name says so; an unchanged name or a vanished
     * entity says nothing; a failure shows its text and keeps the old name.
     */
    fun rename(id: Long) {
        if (dialog.value != ListDialog.Rename(id) || TextWhitespace.isBlank(renameText)) return
        val kind = shownKind()
        val name = renameText
        setDialog(null)
        viewModelScope.launch {
            val result = entityRepository.rename(kind, id, name)
            postEntityDone(kind, result, R.string.person_renamed, R.string.project_renamed) { renamed -> renamed }
        }
    }

    /** "Delete…" of the overflow menu: reads the shown entity's impact, then opens its delete dialog (DEC-113 (5)). */
    fun openDeleteShown() {
        val shown = uiState.value.primary as? Primary.Entity ?: return
        openDelete(shown.id)
    }

    /**
     * "Delete" of the delete dialog of entity [id]: closes it at once and deletes the entity in the transaction of
     * DEC-34, which recomputes the counts. Only while that dialog is open (DEC-113 (4)). The existence probe then
     * forgets the remembered id and the picker body returns (DEC-28, DEC-52).
     */
    fun deleteShown(id: Long) {
        if ((dialog.value as? ListDialog.DeleteEntity)?.id != id) return
        val kind = shownKind()
        setDialog(null)
        viewModelScope.launch {
            val result = when (kind) {
                EntityKind.Person -> entityRepository.deletePerson(id)
                EntityKind.Project -> entityRepository.deleteProject(id)
            }
            postEntityDone(kind, result, R.string.person_deleted, R.string.project_deleted) { it.deleted }
        }
    }

    /**
     * The message of a rename or delete of a [kind] entity: [person] or [project] when [done] says the write happened,
     * nothing when it found nothing to do, the error's text on a failure.
     */
    private fun <T> postEntityDone(
        kind: EntityKind,
        result: AppResult<T>,
        person: Int,
        project: Int,
        done: (T) -> Boolean,
    ) {
        when (result) {
            is AppResult.Ok -> if (done(result.value)) {
                val text = if (kind == EntityKind.Person) person else project
                message.value = UserMessage.of(UiText.Res(text), isError = false)
            }
            is AppResult.Err -> message.value = UserMessage.error(result.error)
        }
    }

    // List position (ui.md § 3.11, DEC-110).

    /**
     * Takes the mark of a note just created, once (DEC-110 (2)). On the picker body of Individual or Project there is
     * no list: the mark is dropped and null returned. Otherwise the same query is read once more, because the rows
     * shown can predate the note, and the wait names that read: only its rows, or a later read's, are judged.
     */
    internal fun takeNewNote(): NewNoteWait? {
        val noteId = newNoteMarker.take() ?: return null
        if (spec.primaryKind != null && uiState.value.primary == Primary.None) return null
        val read = reread.value + 1
        reread.value = read
        return NewNoteWait(noteId, read)
    }

    /** The row the list showed first when it left composition, or null; the next list takes it (DEC-110 (3)). */
    internal fun keepRow(row: KeptRow?) {
        keptRow = row
    }

    /** The kept row, once. */
    internal fun takeKeptRow(): KeptRow? = keptRow.also { keptRow = null }

    /** The snackbar of [id] was shown (architecture.md § Layering rule 7). */
    fun onMessageShown(id: Long) = message.update { if (it?.id == id) null else it }

    // Flows.

    /**
     * Individual or Project's primary (ui.md § 4.2): `Loading` only until the settings are read and the probe of a
     * remembered id has first answered; `None` when nothing is remembered or the entity is gone, whose id is then
     * forgotten. A new choice or a restarted collection keeps the shown primary until the probe answers, so the
     * screen never falls back to `Loading` (ui.md § 3.6: only the rows reload).
     */
    private fun entityPrimary(kind: EntityKind): Flow<Primary> = shownChoice(kind)
        .distinctUntilChanged()
        .flatMapLatest { remembered ->
            val id = remembered?.id
            when {
                remembered == null -> emptyFlow()
                id == null -> flowOf(Primary.None)
                else -> entityRepository.observe(kind, id)
                    .onEach { if (it == null) forget(kind, id) }
                    .map { ref -> ref?.let { Primary.Entity(it.id, it.name) } ?: Primary.None }
            }
        }
        .onStart { emit(uiState.value.primary) }
        .onEach { primary ->
            if (primary == Primary.None) {
                // The picker body has no rows to select, also under a selection restored over a vanished entity.
                if (searchOpen.value) closeSearch()
                endSelection()
            }
            if (primary != Primary.Loading) {
                // A rename keeps the id; another entity is another list (ui.md § 5.5 rule 6).
                val id = (primary as? Primary.Entity)?.id
                if (shownEntity != null && id != shownEntity) endSelection()
                shownEntity = id
                // The rename and delete dialogs belong to the entity they name (DEC-113 (6)).
                if (dialog.value.entityId().let { it != null && it != id }) setDialog(null)
            }
        }

    /** The choice to show: an unsaved one of this screen, else the remembered id; null until the settings are read. */
    private fun shownChoice(kind: EntityKind): Flow<Remembered?> =
        combine(settingsStore.settings, unsavedChoice) { settings, unsaved ->
            settings?.let {
                when (unsaved) {
                    UnsavedChoice.None -> Remembered(it.rememberedId(kind))
                    is UnsavedChoice.Entity -> Remembered(unsaved.id)
                    UnsavedChoice.Cleared -> Remembered(null)
                }
            }
        }

    /**
     * Opens the delete dialog of entity [id] once its impact is read (DEC-113 (5), (6)): nothing for an entity already
     * gone, and nothing unless, after the read, the decided primary is still that entity and no other dialog is open,
     * so a read that returns after another entity or the picker body is shown opens nothing.
     */
    private fun openDelete(id: Long) {
        if (deleteRead?.isActive == true) return
        val kind = shownKind()
        deleteRead = viewModelScope.launch {
            entityRepository.observe(kind, id).first() ?: return@launch
            val impact = when (kind) {
                EntityKind.Person -> entityRepository.personDeleteImpact(id)
                EntityKind.Project -> entityRepository.projectDeleteImpact(id)
            }
            when (impact) {
                is AppResult.Err -> message.value = UserMessage.error(impact.error)
                is AppResult.Ok -> {
                    val decided = listQuery.first { it.primary != Primary.Loading }.primary
                    if ((decided as? Primary.Entity)?.id == id && dialog.value == null) {
                        deleteRead = null
                        setDialog(ListDialog.DeleteEntity(id, impact.value.n, impact.value.m))
                    }
                }
            }
        }
    }

    private fun shownKind(): EntityKind = checkNotNull(spec.primaryKind) { "only Individual and Project show an entity" }

    /** The rows of [query], mapped whenever they or today change, off the main thread (architecture.md § Threading). */
    private fun rowsOf(list: ListQuery, query: NoteQuery, read: Int): Flow<RowsResult> {
        val entity = (list.primary as? Primary.Entity)?.id
        val omitPerson = entity?.takeIf { spec.primaryKind == EntityKind.Person }
        val omitProject = entity?.takeIf { spec.primaryKind == EntityKind.Project }
        return combine(noteRepository.observeRows(query), appTime.todayFlow) { rows, today ->
            RowsResult(list.primaryKey, rows.map { it.toRowUi(today, omitPerson, omitProject) }, read)
        }.flowOn(dispatchers.default)
    }

    /**
     * The name of the secondary filter's entity, for its chip; when the entity disappears, the filter is cleared
     * (DEC-34, data-layer.md § Reactivity). Constant null for a dimension the perspective does not offer.
     */
    private fun entityFilterName(
        kind: EntityKind,
        dimension: FilterDimension,
        filter: MutableStateFlow<Long?>,
    ): Flow<String?> = if (dimension !in spec.filters) {
        flowOf(null)
    } else {
        filter.flatMapLatest { id ->
            id?.let {
                entityRepository.observe(kind, it)
                    .onEach { ref -> if (ref == null) clearVanishedFilter(kind, it) }
                    .map { ref -> ref?.name }
                    .onStart { emit(shownName(dimension, it)) }
            } ?: flowOf(null)
        }
    }

    /** The name the chip of [dimension] shows for [id]; null while it shows another entity or none. */
    private fun shownName(dimension: FilterDimension, id: Long): String? =
        (uiState.value.chips.firstOrNull { it.dimension == dimension }?.value as? ChipValue.Entity)
            ?.takeIf { it.id == id }
            ?.name

    private fun clearVanishedFilter(kind: EntityKind, id: Long) {
        val current = if (kind == EntityKind.Person) personFilter.value else projectFilter.value
        if (current == id) setEntityFilter(kind, null)
    }

    private fun markedDays(query: NoteQuery, shown: YearMonth): Flow<Set<Long>> =
        noteRepository.observeMarkedDays(query, shown.atDay(1).toEpochDay(), shown.atEndOfMonth().toEpochDay())

    /**
     * Forgets [id] if it is still the remembered entity of [kind], compared inside the one settings edit. An unsaved
     * choice of [id] gives way to no entity, not to an older remembered one: the picker shows, as for a remembered id.
     */
    private fun forget(kind: EntityKind, id: Long) {
        if (unsavedChoice.value == UnsavedChoice.Entity(id)) setUnsavedChoice(UnsavedChoice.Cleared)
        viewModelScope.launch {
            try {
                settingsStore.forgetRemembered(kind, id)
            } catch (_: IOException) {
                // The probe keeps reporting the entity gone, so the picker shows either way.
            }
        }
    }

    private fun chipsOf(entities: EntityFilters, date: DateFilter): List<FilterChipUi> =
        spec.filters.map { FilterChipUi(it, entities.chipValue(it, date)) }

    /** A secondary filter or the search is active; the primary never counts (ui.md § 3.6). */
    private fun filtersActive(person: Long?, project: Long?, date: DateFilter, search: String): Boolean =
        person != null || project != null || date != DateFilter.Any || !TextWhitespace.isBlank(search)

    /** What the screen shows before the first emission: everything that needs no query. */
    private fun initialState(): NoteListUiState {
        val today = appTime.todayFlow.value
        return NoteListUiState(
            spec = spec,
            loading = true,
            primary = when {
                spec.showsMonthGrid -> daySelection.value.toPrimary()
                spec.primaryKind != null -> Primary.Loading
                else -> Primary.None
            },
            rows = emptyList(),
            rowsRead = 0,
            newNoteMarked = newNoteMarker.noteId.value != null,
            today = today,
            chips = chipsOf(EntityFilters(personFilter.value, null, projectFilter.value, null), dateFilter.value),
            filtersActive = filtersActive(personFilter.value, projectFilter.value, dateFilter.value, searchText),
            searchOpen = searchOpen.value,
            dateFilter = dateFilter.value,
            selection = selection.value,
            batchRunning = false,
            calendar = if (spec.showsMonthGrid) CalendarUi(month.value, today, daySelection.value, emptySet()) else null,
            dialog = dialog.value,
            message = null,
        )
    }

    // Saved state (ui.md § 3.10): every change goes to the flow and to the SavedStateHandle together.

    private fun setSearchOpen(open: Boolean) = setSaved(searchOpen, KEY_SEARCH_OPEN, open)

    private fun storeSearchText(text: String) {
        searchText = text
        setSaved(searchTextFlow, KEY_SEARCH_TEXT, text)
    }

    private fun applySearchNow(text: String) {
        debounce?.cancel()
        applySearch(text)
    }

    private fun applySearch(text: String) {
        if (appliedSearch.value != text) endSelection()
        appliedSearch.value = text
    }

    /**
     * Runs one batch action over the selection pruned to the current query's ids (ui.md § 5.3, § 5.4): one
     * repository call and one transaction, never a loop over notes (§9, DEC-36). The ids are read from the database,
     * not taken from the last emission, which is stale once the list stopped collecting behind a picker. Success ends
     * selection mode and shows the count of [success]; a failure shows its error and keeps the selection for a retry.
     * Nothing starts while an action runs, and a selection ended or begun anew meanwhile is left alone.
     */
    private fun runBatch(action: suspend (Set<Long>) -> AppResult<BatchResult>, success: (BatchResult) -> UiText) {
        if (selection.value == null || batchRunning.value) return
        batchRunning.value = true
        val session = selectionSession
        viewModelScope.launch {
            try {
                val result = when (val live = currentIds()) {
                    is AppResult.Err -> live
                    is AppResult.Ok -> {
                        if (session != selectionSession) return@launch
                        pruneSelection(live.value)
                        action(selection.value ?: return@launch)
                    }
                }
                when (result) {
                    is AppResult.Ok -> {
                        if (session == selectionSession) endSelection()
                        message.value = UserMessage.of(success(result.value), isError = false)
                    }
                    is AppResult.Err -> {
                        if (session == selectionSession && dialog.value.needsSelection()) setDialog(null)
                        message.value = UserMessage.error(result.error)
                    }
                }
            } finally {
                batchRunning.value = false
            }
        }
    }

    /**
     * The ids of every row of the current query, read once; none while there is no list (the picker body). Waits
     * for Individual or Project to decide its primary, so a restored selection is never measured against no query.
     */
    private suspend fun currentIds(): AppResult<Set<Long>> {
        val query = listQuery.first { it.primary != Primary.Loading }.query ?: return AppResult.Ok(emptySet())
        return when (val ids = noteRepository.selectIds(query)) {
            is AppResult.Ok -> AppResult.Ok(ids.value.toSet())
            is AppResult.Err -> ids
        }
    }

    private fun openSelectionDialog(value: ListDialog) {
        if (selection.value != null && !batchRunning.value) setDialog(value)
    }

    /** Drops the selected ids that [ids] no longer holds; none left ends selection mode (ui.md § 5.5 rule 4). */
    private fun pruneSelection(ids: Set<Long>) {
        val current = selection.value ?: return
        val kept = current.filterTo(HashSet(current.size)) { it in ids }
        if (kept.size != current.size) setSelection(kept.ifEmpty { null })
    }

    private fun endSelection() {
        if (selection.value != null) setSelection(null)
    }

    /**
     * Sets the selection and its saved key, which is absent outside selection mode and above [MAX_SAVED_SELECTION]
     * ids. Leaving selection mode closes its dialogs (ui.md § 3.10).
     */
    private fun setSelection(value: Set<Long>?) {
        if ((value == null) != (selection.value == null)) selectionSession++
        if (value != null && value.size <= MAX_SAVED_SELECTION) {
            savedStateHandle[KEY_SELECTION] = value.toLongArray()
        } else {
            savedStateHandle.remove<LongArray>(KEY_SELECTION)
        }
        selection.value = value
        if (value == null && dialog.value.needsSelection()) setDialog(null)
    }

    private fun setUnsavedChoice(value: UnsavedChoice) {
        savedStateHandle[KEY_UNSAVED_CHOICE] = value.savedValue
        unsavedChoice.value = value
    }

    /**
     * Sets the open dialog and its saved key; the rename text lives only while the rename dialog is open. Any change of
     * the dialog ends an impact read still running, so a read that returns after a Cancel opens nothing.
     */
    private fun setDialog(value: ListDialog?) {
        deleteRead?.cancel()
        savedStateHandle[KEY_LIST_DIALOG] = value?.toKey()
        if (value !is ListDialog.Rename && savedStateHandle.contains(KEY_RENAME_TEXT)) {
            savedStateHandle.remove<String>(KEY_RENAME_TEXT)
            renameText = ""
        }
        dialog.value = value
    }

    private fun storeRenameText(text: String) {
        renameText = text
        savedStateHandle[KEY_RENAME_TEXT] = text
    }

    private fun setDateFilter(filter: DateFilter) {
        if (dateFilter.value != filter) endSelection()
        val range = filter as? DateFilter.Range
        savedStateHandle[KEY_DATE_KIND] = when (filter) {
            DateFilter.Any -> DATE_ANY
            DateFilter.Undated -> DATE_UNDATED
            is DateFilter.Range -> DATE_RANGE
        }
        savedStateHandle[KEY_DATE_FROM] = range?.fromDay
        savedStateHandle[KEY_DATE_TO] = range?.toDay
        dateFilter.value = filter
    }

    private fun setDaySelection(days: DaySelection) {
        if (daySelection.value != days) endSelection()
        saveDaySelection(days)
        daySelection.value = days
    }

    private fun saveDaySelection(selection: DaySelection) {
        savedStateHandle[KEY_DAY] = (selection as? DaySelection.Day)?.epochDay
        savedStateHandle[KEY_RANGE_FROM] = (selection as? DaySelection.Range)?.fromDay
        savedStateHandle[KEY_RANGE_TO] = (selection as? DaySelection.Range)?.toDay
    }

    private fun setMonth(shown: YearMonth) {
        savedStateHandle[KEY_MONTH] = shown.atDay(1).toEpochDay()
        month.value = shown
    }

    private fun <T> setSaved(flow: MutableStateFlow<T>, key: String, value: T) {
        savedStateHandle[key] = value
        flow.value = value
    }

    private fun savedDateFilter(): DateFilter = when (savedStateHandle.get<String>(KEY_DATE_KIND)) {
        DATE_UNDATED -> DateFilter.Undated
        DATE_RANGE -> DateFilter.Range(savedStateHandle[KEY_DATE_FROM], savedStateHandle[KEY_DATE_TO])
        else -> DateFilter.Any
    }

    private fun savedDaySelection(): DaySelection {
        val from = savedStateHandle.get<Long>(KEY_RANGE_FROM)
        val to = savedStateHandle.get<Long>(KEY_RANGE_TO)
        val day = savedStateHandle.get<Long>(KEY_DAY)
        return when {
            from != null && to != null -> DaySelection.Range(from, to)
            day != null -> DaySelection.Day(day)
            else -> DaySelection.Day(appTime.today().toEpochDay())
        }
    }

    private fun savedMonth(): YearMonth = savedStateHandle.get<Long>(KEY_MONTH)
        ?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }
        ?: YearMonth.from(appTime.today())

    /** The primary of a perspective, the filter that keeps only it, and the query of all filters. */
    private data class ListQuery(val primary: Primary, val primaryKey: NoteFilter, val query: NoteQuery?) {
        override fun toString() = "ListQuery($primary, $query)" // both redact the search text
    }

    private class RowsResult(val primaryKey: NoteFilter, val rows: List<NoteRowUi>, val read: Int)

    private class ListPart(
        val loading: Boolean,
        val primary: Primary,
        val rows: List<NoteRowUi>,
        val rowsRead: Int,
        val today: LocalDate,
    )

    private class ScreenPart(
        val dialog: ListDialog?,
        val message: UserMessage?,
        val selection: Set<Long>?,
        val batchRunning: Boolean,
        val newNoteMarked: Boolean,
    )

    private class FilterPart(
        val chips: List<FilterChipUi>,
        val filtersActive: Boolean,
        val searchOpen: Boolean,
        val dateFilter: DateFilter,
    )

    /** A read of the settings: the remembered id of the perspective's kind, null when nothing is remembered. */
    private data class Remembered(val id: Long?) {
        override fun toString() = "Remembered($id)"
    }

    /**
     * A primary choice the settings could not save (DEC-99). [Cleared]: its entity was deleted, or "All people" / "All
     * projects" could not be saved (DEC-112 (3)), so the picker shows rather than an older remembered entity. Saved as
     * one nullable id: null for [None], 0 for [Cleared], which no entity has (Room's generated ids start at 1).
     */
    private sealed interface UnsavedChoice {
        val savedValue: Long?

        data object None : UnsavedChoice {
            override val savedValue: Long? = null
        }

        data class Entity(val id: Long) : UnsavedChoice {
            override val savedValue: Long get() = id

            override fun toString() = "Entity($id)"
        }

        data object Cleared : UnsavedChoice {
            override val savedValue: Long = CLEARED
        }

        companion object {
            private const val CLEARED = 0L

            fun of(saved: Long?): UnsavedChoice = when (saved) {
                null -> None
                CLEARED -> Cleared
                else -> Entity(saved)
            }
        }
    }

    private class EntityFilters(val person: Long?, val personName: String?, val project: Long?, val projectName: String?) {
        fun chipValue(dimension: FilterDimension, date: DateFilter): ChipValue = when (dimension) {
            FilterDimension.Person -> entityValue(person, personName)
            FilterDimension.Project -> entityValue(project, projectName)
            FilterDimension.Date -> if (date == DateFilter.Any) ChipValue.NotSet else ChipValue.Date(date)
        }

        /** Not set until the probe has named the entity, so a chip never shows a stale or empty name. */
        private fun entityValue(id: Long?, name: String?): ChipValue =
            if (id != null && name != null) ChipValue.Entity(id, name) else ChipValue.NotSet
    }

    companion object {
        // The SavedStateHandle keys of ui.md § 3.10.
        const val KEY_PERSON = "personId"
        const val KEY_PROJECT = "projectId"
        const val KEY_DATE_KIND = "dateKind"
        const val KEY_DATE_FROM = "dateFrom"
        const val KEY_DATE_TO = "dateTo"
        const val KEY_SEARCH_OPEN = "searchOpen"
        const val KEY_SEARCH_TEXT = "searchText"
        const val KEY_DAY = "day"
        const val KEY_RANGE_FROM = "rangeFrom"
        const val KEY_RANGE_TO = "rangeTo"
        const val KEY_MONTH = "month"
        const val KEY_LIST_DIALOG = "listDialog"
        const val KEY_SELECTION = "selection"
        const val KEY_UNSAVED_CHOICE = "unsavedChoice"
        const val KEY_HANDLED_LEAVES = "handledLeaves"
        const val KEY_RENAME_TEXT = "renameText"

        private const val DATE_ANY = "Any"
        private const val DATE_UNDATED = "Undated"
        private const val DATE_RANGE = "Range"
    }
}

private fun Settings.rememberedId(kind: EntityKind): Long? = when (kind) {
    EntityKind.Person -> rememberedPersonId
    EntityKind.Project -> rememberedProjectId
}

/** The entity id of the rename or delete dialog of the shown entity; null for every other dialog and for none. */
private fun ListDialog?.entityId(): Long? = when (this) {
    is ListDialog.Rename -> id
    is ListDialog.DeleteEntity -> id
    else -> null
}

/** The dialogs that act on the selection; they close when selection mode ends (ui.md § 3.10). */
private fun ListDialog?.needsSelection(): Boolean = this == ListDialog.SetDate || this == ListDialog.BatchDelete

private fun DaySelection.toPrimary(): Primary = when (this) {
    is DaySelection.Day -> Primary.Day(epochDay)
    is DaySelection.Range -> Primary.Range(fromDay, toDay)
}

/** The Date perspective's days as the primary of its `NoteFilter`; null for every other primary. */
private fun Primary.days(): DateFilter.Range? = when (this) {
    is Primary.Day -> DateFilter.Range(epochDay, epochDay)
    is Primary.Range -> DateFilter.Range(fromDay, toDay)
    else -> null
}
