package com.doro.organizer.ui.common

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R

/** The "Delete" button of a delete dialog, in the error color because it cannot be undone (ui.md § 8.2, § 8.4). */
@Composable
internal fun DestructiveButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.action_delete)) }
}
