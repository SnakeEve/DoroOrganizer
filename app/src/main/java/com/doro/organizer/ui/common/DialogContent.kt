package com.doro.organizer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The `text` slot of every dialog (ui.md § 8.1): body, fields, checkbox and error texts in one scrolling column, so a
 * dialog stays reachable with the keyboard open at 200 % font while the title and the buttons stay fixed.
 */
@Composable
fun DialogContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
