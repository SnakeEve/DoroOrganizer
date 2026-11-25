package com.doro.organizer.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.UiGraph
import com.doro.organizer.ui.common.AppSnackbarHost
import com.doro.organizer.ui.common.DelayedProgress
import com.doro.organizer.ui.common.MessageEffect
import com.doro.organizer.ui.common.PICKER_RESULT
import com.doro.organizer.ui.common.PickerResult
import com.doro.organizer.ui.common.SecureDialogWindow
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.LocalIsTopEntry
import com.doro.organizer.ui.navigation.Navigator

/** Builds the editor's ViewModel in its entry (architecture.md § Lifetimes). */
fun editorFactory(graph: UiGraph, key: EditorKey) = viewModelFactory {
    initializer {
        EditorViewModel(
            key,
            createSavedStateHandle(),
            graph.noteRepository,
            graph.entityRepository,
            graph.settingsStore,
            graph.appMessages,
            graph.newNoteMarker,
            graph.appTime,
            graph.applicationScope,
            graph.dispatchers,
        )
    }
}

/**
 * The note editor of [key] (ui.md § 6): the app bar with Save and the menu, then content, associations, date and
 * the done checkbox. Leaving saves without asking (DEC-37): the entry's `ON_STOP` and the end of its composition
 * both call `saveOnLeave`, which writes only a changed draft, so Back is never intercepted and predictive back stays
 * intact. Pickers opened from here return through the result bus (ui.md § 7.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(key: EditorKey, navigator: Navigator) {
    val graph = LocalUiGraph.current
    val vm: EditorViewModel = viewModel(factory = editorFactory(graph, key))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val isTop = LocalIsTopEntry.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveOnLeave() }
    DisposableEffect(vm) { onDispose { vm.saveOnLeave() } }
    LaunchedEffect(state.closed, isTop) { if (state.closed && isTop) navigator.back() }
    ResultEffect<PickerResult>(PICKER_RESULT, LocalResultEventBus.current) { vm.onPickerResult(it) }
    MessageEffect(state.message, graph, vm::onMessageShown)
    LaunchedEffect(state.loading) {
        if (!state.loading && vm.wantsFocus) {
            contentFocus.requestFocus()
            keyboard?.show()
            vm.onFocusRequested()
        }
    }

    // A dialog, sheet or picker hides the keyboard; returning does not bring it back (ui.md § 6.13).
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    val open = { target: NavKey ->
        dismissKeyboard()
        navigator.open(target)
    }
    val openDialog = { dialog: EditorDialog ->
        dismissKeyboard()
        vm.openDialog(dialog)
    }
    val title = stringResource(if (state.isNew) R.string.editor_title_new else R.string.editor_title_edit)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = vm::save, enabled = !state.loading) {
                        Icon(painterResource(R.drawable.ic_check), stringResource(R.string.action_save))
                    }
                    EditorMenu(
                        enabled = !state.loading,
                        canUnlink = state.people.isNotEmpty() || state.projects.isNotEmpty(),
                        onUnlink = { openDialog(EditorDialog.Unlink) },
                        onDeleteEntity = { kind -> open(vm.deletePicker(kind, navigator.nextVisit())) },
                        onDeleteNote = { openDialog(EditorDialog.DeleteNote) },
                    )
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        if (state.loading) {
            DelayedProgress(Modifier.padding(padding).fillMaxSize())
        } else {
            EditorBody(
                state = state,
                vm = vm,
                padding = padding,
                contentFocus = contentFocus,
                onAddLinks = { kind -> open(vm.linksPicker(kind, navigator.nextVisit())) },
                onOpenDate = { openDialog(EditorDialog.DatePicker) },
            )
        }
    }
    // A dialog restored after a process death opens once the draft has loaded: none acts on a draft not loaded (§ 6.14).
    if (!state.loading) EditorDialogs(state, vm)
}

/** The overflow menu (ui.md § 6.2), in the §3 order; UI-local, so it is not restored after a process death. */
@Composable
private fun EditorMenu(
    enabled: Boolean,
    canUnlink: Boolean,
    onUnlink: () -> Unit,
    onDeleteEntity: (EntityKind) -> Unit,
    onDeleteNote: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val item = @Composable { label: Int, itemEnabled: Boolean, action: () -> Unit ->
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            enabled = itemEnabled,
            onClick = {
                open = false
                action()
            },
        )
    }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            SecureDialogWindow {
                item(R.string.menu_unlink, canUnlink, onUnlink)
                item(R.string.menu_delete_person, true) { onDeleteEntity(EntityKind.Person) }
                item(R.string.menu_delete_project, true) { onDeleteEntity(EntityKind.Project) }
                item(R.string.menu_delete_note, true, onDeleteNote)
            }
        }
    }
}
