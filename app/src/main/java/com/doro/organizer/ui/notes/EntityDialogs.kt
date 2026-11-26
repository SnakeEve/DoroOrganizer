package com.doro.organizer.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.model.capCharsLength
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.EntityDeleteDialog
import com.doro.organizer.ui.common.NoPersonalizedLearning
import com.doro.organizer.ui.common.SecureDialogWindow

/** What the overflow menu of Individual and Project does to the entity the tab shows (ui.md § 4.2, DEC-113). */
internal class EntityMenuActions(val onRename: () -> Unit, val onDelete: () -> Unit)

/**
 * The overflow button of the shown person or project and its menu, "Rename…" and "Delete…" (DEC-113 (1)), at the
 * end of the perspective's top bar. UI-local, like every overflow menu, so it is not restored after a process death
 * (ui.md § 3.10); neither item navigates.
 */
@Composable
internal fun EntityMenu(actions: EntityMenuActions) {
    var open by remember { mutableStateOf(false) }
    val item = @Composable { label: Int, action: () -> Unit ->
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            onClick = {
                open = false
                action()
            },
        )
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("entity_menu")) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SecureDialogWindow {
                item(R.string.menu_rename_entity, actions.onRename)
                item(R.string.menu_delete_entity, actions.onDelete)
            }
        }
    }
}

/**
 * The rename or delete dialog of the person or project Individual or Project shows (DEC-113), composed only while the
 * tab shows the entity whose id the dialog holds (DEC-113 (6)). [focusRename] is the screen's one-shot request that
 * the rename field take focus and show the keyboard; [onRenameFocused] reports that it did. [onDelete] is "Delete" of
 * the delete dialog, which the screen handles before the ViewModel (DEC-113 (5)).
 */
@Composable
internal fun ShownEntityDialog(
    state: NoteListUiState,
    vm: NoteListViewModel,
    focusRename: Boolean,
    onRenameFocused: () -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    val shown = state.primary as? Primary.Entity ?: return
    val kind = state.spec.primaryKind ?: return
    when (val dialog = state.dialog) {
        is ListDialog.Rename -> if (dialog.id == shown.id) {
            RenameEntityDialog(
                kind = kind,
                text = vm.renameText,
                focusOnStart = focusRename,
                onFocusedOnStart = onRenameFocused,
                onTextChange = vm::onRenameTextChange,
                onConfirm = { vm.rename(dialog.id) },
                onDismiss = vm::closeDialog,
            )
        }
        is ListDialog.DeleteEntity -> if (dialog.id == shown.id) {
            EntityDeleteDialog(
                kind = kind,
                name = shown.name,
                n = dialog.n,
                m = dialog.m,
                onConfirm = { onDelete(dialog.id) },
                onDismiss = vm::closeDialog,
            )
        }
        else -> Unit
    }
}

/**
 * "Rename person" / "Rename project" (ui.md § 8.17, DEC-113): one name field that starts with [text] and the caret at
 * its end, capped at `MAX_NAME_CHARS` ([capName]); "Rename", and the keyboard's Done or a hardware Enter, confirm while
 * the field is not blank by `TextWhitespace`. No hint for a name another entity holds (Q23). With [focusOnStart] the
 * field takes focus and shows the keyboard once ([onFocusedOnStart] reports it); a restored dialog asks for neither.
 */
@Composable
internal fun RenameEntityDialog(
    kind: EntityKind,
    text: String,
    focusOnStart: Boolean,
    onFocusedOnStart: () -> Unit,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(renameFieldValue(text)) }
    val title = if (kind == EntityKind.Person) R.string.dialog_rename_person_title else R.string.dialog_rename_project_title
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            SecureDialogWindow {
                DialogContent {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(Unit) {
                        if (focusOnStart) {
                            focus.requestFocus()
                            keyboard?.show()
                            onFocusedOnStart()
                        }
                    }
                    // Always wrapped: the keyboard must never learn a name (DEC-71, Q11).
                    NoPersonalizedLearning {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { changed ->
                                value = capName(changed)
                                onTextChange(value.text)
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("rename_field"),
                            label = { Text(stringResource(R.string.rename_name_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            // Reads the field's current text: an edit and the action can land in one frame.
                            keyboardActions = KeyboardActions(onDone = { if (confirmable(value.text)) onConfirm() }),
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmable(value.text)) {
                Text(stringResource(R.string.action_rename))
            }
        },
    )
}

/** Whether "Rename" may confirm [text]: not blank by `TextWhitespace` (DEC-113 (2)). */
private fun confirmable(text: String): Boolean = !TextWhitespace.isBlank(text)

/**
 * The rename field's first value (ui.md § 8.17): [text] with the caret at its end. A `TextFieldValue`, because the
 * `String` overload of the text field starts its caret at 0, before the name (DEC-113).
 */
internal fun renameFieldValue(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))

/**
 * [value] cut to at most `MAX_NAME_CHARS` UTF-16 units without a message, a surrogate pair kept whole, with its
 * selection and composition moved into the kept text: the rule of the picker's name field (ui.md § 7.2, § 8.17).
 */
internal fun capName(value: TextFieldValue): TextFieldValue {
    val kept = capCharsLength(value.text, MAX_NAME_CHARS)
    if (kept == value.text.length) return value
    return TextFieldValue(
        text = value.text.substring(0, kept),
        selection = value.selection.coerceIn(0, kept),
        composition = value.composition?.coerceIn(0, kept)?.takeUnless { it.collapsed },
    )
}
