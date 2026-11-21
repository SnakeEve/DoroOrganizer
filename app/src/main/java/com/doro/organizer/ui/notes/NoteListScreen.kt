package com.doro.organizer.ui.notes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.window.core.layout.WindowSizeClass
import com.doro.organizer.R
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.UiGraph
import com.doro.organizer.ui.common.AppSnackbarHost
import com.doro.organizer.ui.common.DateFormats
import com.doro.organizer.ui.common.DelayedProgress
import com.doro.organizer.ui.common.EntityPicker
import com.doro.organizer.ui.common.EntityPickerViewModel
import com.doro.organizer.ui.common.LocalAppSnackbar
import com.doro.organizer.ui.common.MonthGrid
import com.doro.organizer.ui.common.rememberAnnouncer
import com.doro.organizer.ui.common.rememberDateFormats
import com.doro.organizer.ui.common.resolve
import com.doro.organizer.ui.navigation.LocalIsTopEntry

/** Builds the list's ViewModel in the tab root's entry (architecture.md § Lifetimes). */
fun noteListFactory(graph: UiGraph, spec: PerspectiveSpec) = viewModelFactory {
    initializer {
        NoteListViewModel(
            spec,
            createSavedStateHandle(),
            graph.noteRepository,
            graph.entityRepository,
            graph.settingsStore,
            graph.appTime,
            graph.dispatchers,
        )
    }
}

/** Builds the embedded picker's ViewModel of Individual or Project in the same entry. */
private fun entityPickerFactory(graph: UiGraph, kind: EntityKind) = viewModelFactory {
    initializer { EntityPickerViewModel(kind, createSavedStateHandle(), graph.entityRepository, graph.appTime) }
}

/**
 * One perspective (ui.md § 3, § 4): the app bar or the search bar, the filter chips, and the list, the Calendar's
 * grid, or the embedded entity picker. [reselectCount] grows when the user taps this tab while it is current, which
 * scrolls the list to the top (ui.md § 2.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(perspective: Perspective, reselectCount: Int) {
    val graph = LocalUiGraph.current
    val spec = remember(perspective) { PerspectiveSpec.of(perspective) }
    val vm: NoteListViewModel = viewModel(factory = noteListFactory(graph, spec))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val formats = rememberDateFormats()
    val listState = rememberLazyListState()
    val pickerListState = rememberLazyListState()
    val pickerFocus = remember { FocusRequester() }
    // Not saveable: a bar restored after a process death reopens without focus or keyboard (ui.md § 3.3).
    var focusSearch by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pickerShown = spec.primaryKind != null && state.primary == Primary.None
    val title = (state.primary as? Primary.Entity)?.name ?: stringResource(spec.title)

    // P-5 times the cold start to the first list with data (architecture.md § Performance budgets).
    if (perspective == Perspective.All) ReportDrawnWhen { !state.loading }
    BackHandler(enabled = state.searchOpen, onBack = vm::closeSearch)
    ReselectEffect(reselectCount, if (pickerShown) pickerListState else listState)
    MessageEffect(state.message, graph, vm::onMessageShown)

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .semantics { paneTitle = title },
        topBar = {
            if (state.searchOpen) {
                SearchBar(
                    text = vm.searchText,
                    focusOnStart = focusSearch,
                    onTextChange = vm::onSearchTextChange,
                    onSubmit = vm::submitSearch,
                    onClear = vm::clearSearchText,
                    onClose = vm::closeSearch,
                )
            } else {
                PerspectiveTopBar(
                    title = title,
                    onSearch = {
                        // With the picker body, Search goes to the picker's own name field (ui.md § 2.2, § 4.2);
                        // while Individual or Project has not yet decided between picker and list, it does nothing.
                        when {
                            pickerShown -> pickerFocus.requestFocus()
                            state.primary == Primary.Loading -> Unit
                            else -> {
                                focusSearch = true
                                vm.openSearch()
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.primary == Primary.Loading -> DelayedProgress(Modifier.fillMaxSize())
                pickerShown -> {
                    val kind = checkNotNull(spec.primaryKind)
                    val picker: EntityPickerViewModel =
                        viewModel(key = "picker", factory = entityPickerFactory(graph, kind))
                    val pickerState by picker.uiState.collectAsStateWithLifecycle()
                    EntityPicker(
                        kind = kind,
                        search = picker.search,
                        state = pickerState,
                        onSearchChange = picker::onSearchChange,
                        onPick = vm::choosePrimary,
                        searchFocus = pickerFocus,
                        listState = pickerListState,
                    )
                }
                else -> ListContent(state, vm, graph, formats, listState)
            }
        }
    }
    ListDialogs(state, vm, formats)
}

@Composable
private fun ListContent(
    state: NoteListUiState,
    vm: NoteListViewModel,
    graph: UiGraph,
    formats: DateFormats,
    listState: LazyListState,
) {
    // The Person and Project chips open the EntityPicker destination (PickerKey, SPEC T18); until it exists only the
    // Date chip is offered.
    val chips = state.chips.filter { it.dimension == FilterDimension.Date }
    if (chips.isNotEmpty()) FilterChipRow(chips, formats) { vm.openDateFilter() }
    val announcer = rememberAnnouncer(graph)
    val markedDone = stringResource(R.string.announce_marked_done)
    val markedNotDone = stringResource(R.string.announce_marked_not_done)
    val onCheckedChange: RowToggle = { row, checked, onDone ->
        vm.setChecked(row.id, checked) { changed ->
            if (changed) announcer.announce(if (checked) markedDone else markedNotDone)
            onDone(changed)
        }
    }
    val calendar = state.calendar
    if (calendar == null) {
        NoteListBody(state, listState, formats, onCheckedChange, vm::clearFilters)
    } else {
        CalendarContent(state, calendar, vm, formats, listState, onCheckedChange)
    }
}

/**
 * The Date perspective (ui.md § 4.4): one column whose first items are the grid and the selection header, or two
 * panes when the window is expanded or a landscape phone, where a single column would put the day list below the fold.
 */
@Composable
private fun CalendarContent(
    state: NoteListUiState,
    calendar: CalendarUi,
    vm: NoteListViewModel,
    formats: DateFormats,
    listState: LazyListState,
    onCheckedChange: RowToggle,
) {
    val grid = @Composable { modifier: Modifier ->
        MonthGrid(
            month = calendar.month,
            today = calendar.today,
            selection = calendar.selection,
            markedDays = calendar.markedDays,
            firstDayOfWeek = formats.firstDayOfWeek,
            onMonthChange = vm::showMonth,
            onDayClick = vm::selectDay,
            onToday = vm::showToday,
            modifier = modifier,
        )
    }
    val header = @Composable {
        CalendarSelectionHeader(calendar.selection, calendar.today, formats, vm::openRangePicker, vm::clearRange)
    }
    val sizes = currentWindowAdaptiveInfoV2().windowSizeClass
    val twoPane = sizes.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ||
        sizes.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
        !sizes.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                grid(Modifier.fillMaxWidth())
                header()
            }
            NoteListBody(state, listState, formats, onCheckedChange, vm::clearFilters, Modifier.weight(1f), header = {})
        }
    } else {
        NoteListBody(state, listState, formats, onCheckedChange, vm::clearFilters, header = {
            item(key = "month_grid") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    grid(Modifier.widthIn(max = 480.dp))
                }
            }
            item(key = "selection_header") { header() }
        })
    }
}

/** The sheet or dialog that [NoteListUiState.dialog] names (ui.md § 3.10); each is composed by this screen. */
@Composable
private fun ListDialogs(state: NoteListUiState, vm: NoteListViewModel, formats: DateFormats) {
    when (val dialog = state.dialog) {
        null -> Unit
        ListDialog.DateFilterSheet, is ListDialog.DateEndPicker -> {
            DateFilterSheet(
                filter = state.dateFilter,
                offersUndated = state.spec.offersUndated,
                formats = formats,
                onSelect = vm::selectDate,
                onSelectRange = vm::selectDateRange,
                onEditEnd = vm::openDateEndPicker,
                onClearEnd = vm::clearRangeEnd,
                onDismiss = vm::closeDialog,
            )
            if (dialog is ListDialog.DateEndPicker) {
                DateEndPickerDialog(
                    end = dialog.end,
                    filter = state.dateFilter,
                    today = state.today,
                    onConfirm = { vm.setRangeEnd(dialog.end, it) },
                    onDismiss = vm::dismissDateEndPicker,
                )
            }
        }
        ListDialog.RangePicker -> state.calendar?.let { calendar ->
            RangePickerDialog(calendar.selection, onConfirm = vm::setRange, onDismiss = vm::closeDialog)
        }
    }
}

/** A tap on the current tab scrolls the list, or the embedded picker's list, to the top (ui.md § 2.1). */
@Composable
private fun ReselectEffect(reselectCount: Int, target: LazyListState) {
    var handled by rememberSaveable { mutableIntStateOf(reselectCount) }
    LaunchedEffect(reselectCount) {
        if (reselectCount > handled) {
            handled = reselectCount
            target.animateScrollToItem(0)
        }
    }
}

/**
 * Shows the screen's message in the shell's snackbar while this entry is on top, then clears it; a message whose
 * screen left first shows when it returns (ui.md § 2.4).
 */
@Composable
private fun MessageEffect(message: UserMessage?, graph: UiGraph, onShown: (Long) -> Unit) {
    val snackbar = LocalAppSnackbar.current
    val isTop = LocalIsTopEntry.current
    val resources = LocalResources.current
    LaunchedEffect(message?.id, isTop) {
        if (message == null || !isTop) return@LaunchedEffect
        snackbar.showSnackbar(
            message = resources.resolve(message.text, graph.appTime),
            duration = if (message.isError) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        onShown(message.id)
    }
}
