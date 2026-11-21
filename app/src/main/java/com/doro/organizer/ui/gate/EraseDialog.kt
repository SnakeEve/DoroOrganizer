package com.doro.organizer.ui.gate

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.doro.organizer.R
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.SecureDialogWindow
import java.util.Locale

/** The typed confirmation of an Erase (ui.md § 8.10): the trimmed input equals [word], ignoring case (`Locale.ROOT`). */
fun isEraseConfirmation(input: String, word: String): Boolean =
    TextWhitespace.trim(input).uppercase(Locale.ROOT) == word.uppercase(Locale.ROOT)

/**
 * "Erase all data?" with its typed confirmation (ui.md § 8.10, DEC-63). The input is no secret, so it survives
 * rotation; no password or biometric is asked (security.md § 5.4).
 */
@Composable
internal fun EraseDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val word = stringResource(R.string.erase_word)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.erase_title)) },
        text = {
            SecureDialogWindow(hideOverlays = true) {
                DialogContent {
                    Text(stringResource(R.string.erase_body))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.erase_prompt, word)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("erase_confirmation"),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isEraseConfirmation(input, word),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_erase)) }
        },
    )
}
