package com.doro.organizer.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind

/**
 * The impact dialog of a person or project delete (ui.md § 8.4, DEC-34), shared by the editor's delete picker and the
 * overflow menu of People and Projects (DEC-113): [name] in the title; sentence A, sentence B when [n] ≥ 1, then "This
 * cannot be undone.", joined with single spaces. [n] notes are linked to the entity, [m] of them would become orphans.
 * "Delete" is destructive.
 */
@Composable
fun EntityDeleteDialog(
    kind: EntityKind,
    name: String,
    n: Int,
    m: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val person = kind == EntityKind.Person
    val title = stringResource(if (person) R.string.dialog_delete_person_title else R.string.dialog_delete_project_title, name)
    val body = listOfNotNull(impactSentenceA(kind, n), impactSentenceB(n, m))
        .plus(stringResource(R.string.dialog_irreversible))
        .joinToString(" ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { SecureDialogWindow { DialogContent { Text(body) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { DestructiveButton(onConfirm) },
    )
}

/** "This person is linked to N notes." or, for N = 0, "… is not linked to any note." */
@Composable
private fun impactSentenceA(kind: EntityKind, n: Int): String = when {
    n == 0 && kind == EntityKind.Person -> stringResource(R.string.impact_person_not_linked)
    n == 0 -> stringResource(R.string.impact_project_not_linked)
    kind == EntityKind.Person -> pluralStringResource(R.plurals.impact_person_linked, n, n)
    else -> pluralStringResource(R.plurals.impact_project_linked, n, n)
}

/** Which of the N linked notes move to Orphans (M of them, M ⊆ N); none for N = 0. */
@Composable
private fun impactSentenceB(n: Int, m: Int): String? = when {
    n == 0 -> null
    m == 0 -> pluralStringResource(R.plurals.impact_none_orphaned, n)
    m == n -> pluralStringResource(R.plurals.impact_all_orphaned, n)
    else -> pluralStringResource(R.plurals.impact_some_orphaned, m, m)
}
