package com.doro.organizer.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.doro.organizer.R

/** The text under a field: an error is error-styled and a polite live region (ui.md § 8.1), a note is neither. */
class FieldMessage(val text: String, val isError: Boolean)

/**
 * `PasswordField` (ui.md § 8.1, security.md § 6.2): a secure field that never reveals the last typed character, with a
 * show/hide toggle. The caller owns [state] as `remember { TextFieldState() }`, never saveable, so no password reaches
 * the saved-instance Bundle. [onSubmit] runs on the IME action.
 */
@Composable
fun PasswordField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    message: FieldMessage? = null,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) } // the toggle only; the text is never saved
    OutlinedSecureTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                )
            }
        },
        supportingText = message?.let { m ->
            {
                val live = if (m.isError) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
                Text(m.text, modifier = live)
            }
        },
        isError = message?.isError == true,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        onKeyboardAction = onSubmit?.let { submit -> KeyboardActionHandler { submit() } },
    )
}

/**
 * Hands the typed password over as a new array and empties the field (security.md § 6.2): the receiver owns the array
 * and zeroes it; the UI keeps no reference.
 */
fun TextFieldState.takePassword(): CharArray = copyPassword().also { clearText() }

/** A new array with the field's text, built without a `String` (DEC-64); the receiver owns and zeroes it. */
fun TextFieldState.copyPassword(): CharArray = CharArray(text.length) { text[it] }
