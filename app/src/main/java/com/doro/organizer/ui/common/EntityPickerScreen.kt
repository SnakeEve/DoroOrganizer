package com.doro.organizer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.UiGraph
import com.doro.organizer.ui.navigation.PickerKey
import com.doro.organizer.ui.navigation.PickerPurpose

private fun pickerFactory(graph: UiGraph, key: PickerKey) = viewModelFactory {
    initializer {
        EntityPickerViewModel(key.kind, createSavedStateHandle(), graph.entityRepository, graph.appTime, key.purpose)
    }
}

/**
 * The full-screen `EntityPicker` destination of [key] (ui.md § 7.2): the app bar, the two tabs of `BatchAssociate`,
 * the search field and the list. A single-select tap or the confirm button hands the result to [onResult], which
 * sends it and closes the picker (ui.md § 7.7); [onBack], the Cancel or Back icon, closes it without a result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityPickerScreen(key: PickerKey, onResult: (PickerResult) -> Unit, onBack: () -> Unit) {
    val graph = LocalUiGraph.current
    val vm: EntityPickerViewModel = viewModel(factory = pickerFactory(graph, key))
    if (vm.selectionLost) {
        // As the Set date and Delete dialogs: nothing is left to act on, so the picker closes (DEC-57).
        LaunchedEffect(vm) { onBack() }
        return
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val multi = key.purpose.isMultiSelect
    val title = stringResource(pickerTitle(key))
    // One result per visit: a second tap in the frame before the pop must not send again or pop the opener.
    val currentOnResult by rememberUpdatedState(onResult)
    var sent by remember { mutableStateOf(false) }
    val finish = { result: PickerResult ->
        if (!sent) {
            sent = true
            currentOnResult(result)
        }
    }
    val searchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (multi) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_cancel))
                        } else {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                        }
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (multi) {
                        TextButton(onClick = { finish(vm.confirm()) }, enabled = state.canConfirm && !state.loading) {
                            Text(stringResource(confirmLabel(key.purpose)))
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (key.purpose is PickerPurpose.BatchAssociate) PickerTabs(state, vm::selectTab)
            EntityPicker(
                kind = state.tab,
                purpose = key.purpose,
                search = vm.search,
                state = state,
                actions = PickerActions(
                    onSearchChange = vm::onSearchChange,
                    onPick = { finish(vm.pick(it)) },
                    onToggle = vm::toggle,
                    onCreate = vm::create,
                    onDiscard = vm::discard,
                ),
                searchFocus = searchFocus,
                listState = listState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** "People" and "Projects", each with its count of chosen entities while it has any (ui.md § 7.2). */
@Composable
private fun PickerTabs(state: EntityPickerUiState, onSelect: (EntityKind) -> Unit) {
    val tabs = listOf(EntityKind.Person to R.string.tab_people, EntityKind.Project to R.string.tab_projects)
    PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.tab }) {
        tabs.forEach { (kind, label) ->
            val count = state.chosen[kind] ?: 0
            val name = stringResource(label)
            Tab(
                selected = kind == state.tab,
                onClick = { onSelect(kind) },
                text = {
                    Text(if (count > 0) pluralStringResource(R.plurals.tab_with_count, count, name, count) else name)
                },
            )
        }
    }
}

private fun pickerTitle(key: PickerKey): Int {
    val person = key.kind == EntityKind.Person
    return when (key.purpose) {
        is PickerPurpose.EditorLinks -> if (person) R.string.picker_title_add_people else R.string.picker_title_add_projects
        is PickerPurpose.BatchAssociate -> R.string.picker_title_associate
        is PickerPurpose.PerspectivePrimary ->
            if (person) R.string.picker_title_choose_person else R.string.picker_title_choose_project
        is PickerPurpose.SecondaryFilter ->
            if (person) R.string.picker_title_filter_person else R.string.picker_title_filter_project
        is PickerPurpose.DeleteEntity ->
            if (person) R.string.picker_title_delete_person else R.string.picker_title_delete_project
    }
}

private fun confirmLabel(purpose: PickerPurpose): Int =
    if (purpose is PickerPurpose.BatchAssociate) R.string.action_associate else R.string.action_done
