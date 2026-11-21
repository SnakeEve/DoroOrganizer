package com.doro.organizer.ui.notes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.window.core.layout.WindowSizeClass
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.UiGraph
import com.doro.organizer.ui.common.AppSnackbarHost
import com.doro.organizer.ui.common.DateFormats
import com.doro.organizer.ui.common.DelayedProgress
import com.doro.organizer.ui.common.EntityPicker
import com.doro.organizer.ui.common.EntityPickerViewModel
import com.doro.organizer.ui.common.MessageEffect
import com.doro.organizer.ui.common.MonthGrid
import com.doro.organizer.ui.common.PICKER_RESULT
import com.doro.organizer.ui.common.PickerActions
import com.doro.organizer.ui.common.PickerResult
import com.doro.organizer.ui.common.rememberAnnouncer
import com.doro.organizer.ui.common.rememberDateFormats
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.Navigator
import com.doro.organizer.ui.navigation.PickerKey
import com.doro.organizer.ui.navigation.PickerPurpose
import com.doro.organizer.ui.navigation.SettingsKey

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
 * One perspective (ui.md § 3, § 4): the app bar, the search bar or the contextual bar of selection mode (§ 5), the
 * filter chips, and the list, the Calendar's grid, or the embedded entity picker, with the create button outside
 * selection mode. [leaveCount] grows when the user leaves this tab, which ends selection mode; [reselectCount] grows
 * when the user taps this tab while it is current, which scrolls the list to the top (ui.md § 2.1). Rows, the create
 * button, the entity chips, the title button and "Associate" open their destinations through [navigator]; the
 * pickers' results come back through the result bus (ui.md § 7.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(perspective: Perspective, leaveCount: Int, reselectCount: Int, navigator: Navigator) {
    val graph = LocalUiGraph.current
    val spec = remember(perspective) { PerspectiveSpec.of(perspective) }
    val vm: NoteListViewModel = viewModel(factory = noteListFactory(graph, spec))
    val latest by vm.uiState.collectAsStateWithLifecycle()
    // A leave not handled yet already ends selection mode in the returning tab's first frame, before onTabLeft runs:
    // a tab is not composed while another is current, so its retained state still holds the old selection (§ 5.5).
    val state = if (leaveCount > vm.handledLeaves) latest.copy(selection = null) else latest
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
    LaunchedEffect(leaveCount) { vm.onTabLeft(leaveCount) }
    // Composed after the search bar's handler, so selection mode takes Back first (ui.md § 2.5).
    BackHandler(enabled = state.searchOpen, onBack = vm::closeSearch)
    BackHandler(enabled = state.selecting, onBack = vm::exitSelection)
    ReselectEffect(reselectCount, if (pickerShown) pickerListState else listState)
    MessageEffect(state.message, graph, vm::onMessageShown)
    ResultEffect<PickerResult>(PICKER_RESULT, LocalResultEventBus.current) { result ->
        when (result) {
            is PickerResult.Filter -> vm.setEntityFilter(result.kind, result.id)
            is PickerResult.Primary -> vm.choosePrimary(result.id)
            is PickerResult.Associate ->
                vm.associate(result.personIds, result.projectIds, result.newPersons, result.newProjects)
            is PickerResult.Links, is PickerResult.Delete -> Unit
        }
    }
    // Hidden in selection mode (DEC-36) and until Individual or Project has decided between picker and list.
    val fabShown = !state.selecting && state.primary != Primary.Loading
    val bottomPadding = if (fabShown) FAB_CLEARANCE else 0.dp

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .semantics { paneTitle = title },
        topBar = {
            val selection = state.selection
            if (selection != null) {
                SelectionTopBar(
                    count = selection.size,
                    enabled = !state.batchRunning,
                    onExit = vm::exitSelection,
                    onSelectAll = vm::selectAll,
                    onAssociate = {
                        navigator.open(PickerKey(EntityKind.Person, PickerPurpose.BatchAssociate, navigator.nextVisit()))
                    },
                    onSetDate = vm::openSetDate,
                    onDelete = vm::openBatchDelete,
                )
            } else if (state.searchOpen) {
                SearchBar(
                    text = vm.searchText,
                    focusOnStart = focusSearch,
                    // Used once: a bar that returns after selection mode has neither focus nor keyboard (ui.md § 3.3).
                    onFocusedOnStart = { focusSearch = false },
                    onTextChange = vm::onSearchTextChange,
                    onSubmit = vm::submitSearch,
                    onClear = vm::clearSearchText,
                    onClose = vm::closeSearch,
                )
            } else {
                val chosen = state.primary as? Primary.Entity
                PerspectiveTopBar(
                    title = title,
                    onTitleClick = chosen?.let { { navigator.open(primaryPickerKey(spec, it, navigator.nextVisit())) } },
                    titleClickLabel = spec.primaryKind?.let {
                        stringResource(if (it == EntityKind.Person) R.string.a11y_change_person else R.string.a11y_change_project)
                    },
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
                    onSettings = { navigator.open(SettingsKey()) },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { AppSnackbarHost() },
        floatingActionButton = {
            if (fabShown) {
                FloatingActionButton(
                    onClick = { navigator.open(createNoteKey(spec, state.primary, navigator.nextVisit())) },
                    modifier = Modifier.testTag("fab_create"),
                ) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.fab_create_note))
                }
            }
        },
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
                        purpose = null,
                        search = picker.search,
                        state = pickerState,
                        actions = PickerActions(
                            onSearchChange = picker::onSearchChange,
                            onPick = { id -> id?.let(vm::choosePrimary) },
                        ),
                        searchFocus = pickerFocus,
                        listState = pickerListState,
                        contentPadding = PaddingValues(bottom = bottomPadding),
                    )
                }
                else -> ListContent(state, vm, graph, formats, listState, navigator, bottomPadding)
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
    navigator: Navigator,
    bottomPadding: Dp,
) {
    // Selection mode disables the chips, so no filter change drops the selection (ui.md § 5.1).
    if (state.chips.isNotEmpty()) {
        FilterChipRow(state.chips, formats, enabled = !state.selecting) { dimension ->
            when (dimension) {
                FilterDimension.Date -> vm.openDateFilter()
                FilterDimension.Person, FilterDimension.Project -> {
                    val chip = state.chips.first { it.dimension == dimension }
                    navigator.open(filterPickerKey(state.spec, state.primary, chip, navigator.nextVisit()))
                }
            }
        }
    }
    val announcer = rememberAnnouncer(graph)
    val markedDone = stringResource(R.string.announce_marked_done)
    val markedNotDone = stringResource(R.string.announce_marked_not_done)
    val onCheckedChange: RowToggle = { row, checked, onDone ->
        vm.setChecked(row.id, checked) { changed ->
            if (changed) announcer.announce(if (checked) markedDone else markedNotDone)
            onDone(changed)
        }
    }
    val rowActions = remember(vm, navigator) {
        RowActions(
            open = { navigator.open(EditorKey(noteId = it, visit = navigator.nextVisit())) },
            enter = vm::enterSelection,
            toggle = vm::toggleSelection,
        )
    }
    val calendar = state.calendar
    if (calendar == null) {
        NoteListBody(state, listState, formats, onCheckedChange, rowActions, vm::clearFilters, bottomPadding)
    } else {
        CalendarContent(state, calendar, vm, formats, listState, onCheckedChange, rowActions, bottomPadding)
    }
}

/**
 * The Date perspective (ui.md § 4.4): one column whose first items are the grid and the selection header, or two
 * panes when the window is expanded or a landscape phone, where a single column would put the day list below the fold.
 * Selection mode disables the day cells, "Today", "Select range" and "Clear range"; month navigation still works
 * (ui.md § 5.1).
 */
@Composable
private fun CalendarContent(
    state: NoteListUiState,
    calendar: CalendarUi,
    vm: NoteListViewModel,
    formats: DateFormats,
    listState: LazyListState,
    onCheckedChange: RowToggle,
    rowActions: RowActions,
    bottomPadding: Dp,
) {
    val daysEnabled = !state.selecting
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
            daysEnabled = daysEnabled,
        )
    }
    val header = @Composable {
        CalendarSelectionHeader(
            selection = calendar.selection,
            today = calendar.today,
            formats = formats,
            onSelectRange = vm::openRangePicker,
            onClearRange = vm::clearRange,
            enabled = daysEnabled,
        )
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
            NoteListBody(
                state, listState, formats, onCheckedChange, rowActions, vm::clearFilters, bottomPadding,
                Modifier.weight(1f), header = {},
            )
        }
    } else {
        NoteListBody(state, listState, formats, onCheckedChange, rowActions, vm::clearFilters, bottomPadding, header = {
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
        ListDialog.SetDate -> state.selection?.let { selection ->
            // Only the opening value matters: the picker state ignores later initial dates, so it is computed once.
            val shared = remember { sharedDate(state.rows, selection) }
            SetDateDialog(
                shared = shared,
                today = state.today,
                running = state.batchRunning,
                onSet = vm::setDateOfSelection,
                onDismiss = vm::closeDialog,
            )
        }
        ListDialog.BatchDelete -> state.selection?.let { selection ->
            BatchDeleteDialog(
                count = selection.size,
                running = state.batchRunning,
                onConfirm = vm::deleteSelection,
                onDismiss = vm::closeDialog,
            )
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

/** 56 dp button + 16 dp margin + 16 dp gap: the last row scrolls clear of the create button (ui.md § 2.3). */
private val FAB_CLEARANCE = 88.dp
