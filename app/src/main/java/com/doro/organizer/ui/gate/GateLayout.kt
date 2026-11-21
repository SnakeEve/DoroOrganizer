package com.doro.organizer.ui.gate

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.doro.organizer.R

/** The widest a gate screen's column grows (ui.md § 13.4). */
internal val GATE_MAX_WIDTH = 400.dp

private val LOGO_SIZE = 72.dp
private val ICON_SIZE = 48.dp

/**
 * The common layout of the gate screens (ui.md § 10.1): a centered, scrollable column inside the safe-drawing insets,
 * so it holds at 200 % font and in landscape, with the decorative [header], the [title] as a heading and the root's
 * `paneTitle`, then [content]. A gate screen shows no note-derived data (DEC-66).
 */
@Composable
internal fun GateColumn(
    title: String,
    header: @Composable () -> Unit = { AppLogo() },
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = GATE_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                header()
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                content()
            }
        }
    }
}

/** The app logo (ui.md § 10.1): 72 dp, 96 dp on the lock screen; decorative. */
@Composable
internal fun AppLogo(size: Dp = LOGO_SIZE) {
    Image(painterResource(R.drawable.ic_app_logo), contentDescription = null, modifier = Modifier.size(size))
}

/** The error icon of the open error, Corrupt and KeyUnavailable screens (ui.md § 10.5, § 10.7); decorative. */
@Composable
internal fun ErrorIcon(@DrawableRes icon: Int = R.drawable.ic_error) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(ICON_SIZE),
    )
}

/** A body paragraph of a gate screen (`bodyLarge`). */
@Composable
internal fun GateBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}
