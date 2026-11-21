package com.doro.organizer.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.SearchText
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.model.capChars
import com.doro.organizer.model.toQuery
import com.doro.organizer.settings.Settings
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.common.DaySelection
import com.doro.organizer.ui.common.NoteRowUi
import com.doro.organizer.ui.common.toRowUi
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
 * The state holder of one perspective's list (ui.md § 3, § 4), one per tab. Filters, search and the Date selection
 * live in [savedStateHandle] (DEC-28, keys of ui.md § 3.10); the Individual and Project primary is the remembered id
 * of [settingsStore], checked by an existence probe (data-layer.md § Reactivity). The rows come only from the
 * database: a write never patches the state (architecture.md § Layering rule 3). Every input of [uiState] emits at
 * once, starting from what is shown when it restarts, so the search bar, the chips and the dialogs respond before the
 * first rows arrive and a returning tab shows its last list on the first frame (ui.md § 3.6).
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
    private val dialog = MutableStateFlow(listDialogOf(savedStateHandle[KEY_LIST_DIALOG]))
    private val message = MutableStateFlow<UserMessage?>(null)

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

    /** The mapped rows and the primary they belong to; null while no list query runs (the picker body) or answered. */
    private val results: Flow<RowsResult?> = listQuery
        .flatMapLatest { list -> list.query?.let { rowsOf(list, it) } ?: flowOf(null) }
        .onStart { emit(lastResult) }
        .onEach { lastResult = it }

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

    val uiState: StateFlow<NoteListUiState> =
        combine(listPart, filterPart, calendar, dialog, message) { list, filters, calendar, dialog, message ->
            NoteListUiState(
                spec = spec,
                loading = list.loading,
                primary = list.primary,
                rows = list.rows,
                today = list.today,
                chips = filters.chips,
                filtersActive = filters.filtersActive,
                searchOpen = filters.searchOpen,
                dateFilter = filters.dateFilter,
                calendar = calendar,
                dialog = dialog,
                message = message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        // A fresh Date entry opens on the current month with today selected (DEC-53); the values are saved at once,
        // so a later process death restores this selection even after midnight.
        if (spec.showsMonthGrid) {
            saveDaySelection(daySelection.value)
            savedStateHandle[KEY_MONTH] = month.value.atDay(1).toEpochDay()
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
            appliedSearch.value = capped
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
     * Sets or, with null, clears the [kind] filter: the result of the chip's picker (`PickerResult.Filter`, SPEC
     * T18). At most one person and one project (DEC-28).
     */
    fun setEntityFilter(kind: EntityKind, id: Long?) = when (kind) {
        EntityKind.Person -> setSaved(personFilter, KEY_PERSON, id)
        EntityKind.Project -> setSaved(projectFilter, KEY_PROJECT, id)
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
     * title's picker; secondary filters and search stay.
     */
    fun choosePrimary(id: Long) {
        val kind = checkNotNull(spec.primaryKind) { "only Individual and Project have an entity" }
        viewModelScope.launch {
            try {
                settingsStore.setRemembered(kind, id)
            } catch (_: IOException) {
                // A failed DataStore write is a full disk (SettingsStore): nothing was remembered, the picker stays.
                message.value = UserMessage.error(AppError.StorageFull)
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

    /** The snackbar of [id] was shown (architecture.md § Layering rule 7). */
    fun onMessageShown(id: Long) = message.update { if (it?.id == id) null else it }

    // Flows.

    /**
     * Individual or Project's primary (ui.md § 4.2): `Loading` only until the settings are read and the probe of a
     * remembered id has first answered; `None` when nothing is remembered or the entity is gone, whose id is then
     * forgotten. A new choice or a restarted collection keeps the shown primary until the probe answers, so the
     * screen never falls back to `Loading` (ui.md § 3.6: only the rows reload).
     */
    private fun entityPrimary(kind: EntityKind): Flow<Primary> = settingsStore.settings
        .map { settings -> settings?.let { Remembered(it.rememberedId(kind)) } }
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
        .onEach { if (it == Primary.None && searchOpen.value) closeSearch() }

    /** The rows of [query], mapped whenever they or today change, off the main thread (architecture.md § Threading). */
    private fun rowsOf(list: ListQuery, query: NoteQuery): Flow<RowsResult> {
        val entity = (list.primary as? Primary.Entity)?.id
        val omitPerson = entity?.takeIf { spec.primaryKind == EntityKind.Person }
        val omitProject = entity?.takeIf { spec.primaryKind == EntityKind.Project }
        return combine(noteRepository.observeRows(query), appTime.todayFlow) { rows, today ->
            RowsResult(list.primaryKey, rows.map { it.toRowUi(today, omitPerson, omitProject) })
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

    /** Forgets [id] if it is still the remembered entity of [kind], compared inside the one settings edit. */
    private fun forget(kind: EntityKind, id: Long) {
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
            today = today,
            chips = chipsOf(EntityFilters(personFilter.value, null, projectFilter.value, null), dateFilter.value),
            filtersActive = filtersActive(personFilter.value, projectFilter.value, dateFilter.value, searchText),
            searchOpen = searchOpen.value,
            dateFilter = dateFilter.value,
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
        appliedSearch.value = text
    }

    private fun setDialog(value: ListDialog?) {
        savedStateHandle[KEY_LIST_DIALOG] = value?.toKey()
        dialog.value = value
    }

    private fun setDateFilter(filter: DateFilter) {
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

    private fun setDaySelection(selection: DaySelection) {
        saveDaySelection(selection)
        daySelection.value = selection
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

    private class RowsResult(val primaryKey: NoteFilter, val rows: List<NoteRowUi>)

    private class ListPart(val loading: Boolean, val primary: Primary, val rows: List<NoteRowUi>, val today: LocalDate)

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

        private const val DATE_ANY = "Any"
        private const val DATE_UNDATED = "Undated"
        private const val DATE_RANGE = "Range"
    }
}

private fun Settings.rememberedId(kind: EntityKind): Long? = when (kind) {
    EntityKind.Person -> rememberedPersonId
    EntityKind.Project -> rememberedProjectId
}

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
