package com.doro.organizer.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The rows of the Settings screen (ui.md § 9.1): M3 ListItems with a decorative 24 dp leading icon.

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** One `clickable(role = Button)` node; TalkBack reads its visible label. */
@Composable
internal fun ActionRow(
    @DrawableRes icon: Int,
    headline: String,
    supporting: String?,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { RowIcon(icon) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag),
    )
}

/** A row without an action, such as the version. */
@Composable
internal fun InfoRow(@DrawableRes icon: Int, headline: String, supporting: String) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = { RowIcon(icon) },
    )
}

/**
 * One `toggleable(role = Switch)` node whose `Switch` takes no clicks of its own: its name is the headline and its
 * state the switch's. [extra] adds supporting lines and buttons below the supporting text; a button there is its own
 * focusable node.
 */
@Composable
internal fun SwitchRow(
    @DrawableRes icon: Int,
    headline: String,
    supporting: List<String>,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onToggle: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = {
            Column {
                supporting.forEach { Text(it) }
                extra()
            }
        },
        leadingContent = { RowIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .testTag(tag),
    )
}

/** The no-screen-lock hint (DEC-72): informational only, nothing is blocked. */
@Composable
internal fun HintCard(@DrawableRes icon: Int, text: String) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings_no_screen_lock")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Text(text, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}
