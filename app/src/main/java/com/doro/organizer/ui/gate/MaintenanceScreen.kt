package com.doro.organizer.ui.gate

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceStatus

/** The text of a running operation (ui.md § 10.6); also the reason line of a locked password screen meanwhile. */
@StringRes
internal fun MaintenanceOp.textRes(): Int = when (this) {
    MaintenanceOp.Backup -> R.string.maintenance_backup
    MaintenanceOp.Restore -> R.string.maintenance_restore
    MaintenanceOp.KeyChange -> R.string.maintenance_key_change
    MaintenanceOp.StartFresh -> R.string.maintenance_start_fresh
    MaintenanceOp.Erase -> R.string.maintenance_erase
}

/**
 * The standalone maintenance screen (ui.md § 10.6): every origin other than `Ready`, an Erase from any origin, and a
 * running fingerprint reset behind `Locked` (which counts as the unlock, DEC-63). It has no cancel: operations finish
 * or roll back (DEC-87), so Back is consumed.
 */
@Composable
internal fun MaintenanceScreen(op: MaintenanceOp, maintenance: DbMaintenance) {
    BackHandler(enabled = true) {}
    MaintenanceProgress(op, maintenance, Modifier)
}

/**
 * The opaque overlay over the shell while an operation started from `Ready` runs (ui.md § 10.6). It holds the
 * keyboard focus and consumes pointer input, key events and Back, so nothing under it can start a write while the
 * database is in exclusive mode; `DoroRoot` also hides the shell from accessibility services.
 */
@Composable
fun MaintenanceOverlay(op: MaintenanceOp, maintenance: DbMaintenance) {
    val focus = remember { FocusRequester() }
    BackHandler(enabled = true) {}
    LaunchedEffect(Unit) { focus.requestFocus() }
    MaintenanceProgress(
        op,
        maintenance,
        Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { true },
    )
}

@Composable
private fun MaintenanceProgress(op: MaintenanceOp, maintenance: DbMaintenance, modifier: Modifier) {
    val status by maintenance.status.collectAsState()
    val fraction = (status as? MaintenanceStatus.Running)?.takeIf { it.op == op }?.fraction
    val text = stringResource(op.textRes())
    Surface(modifier.fillMaxSize().semantics { paneTitle = text }.testTag("maintenance_overlay")) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = GATE_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    CircularProgressIndicator()
                }
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.maintenance_keep_open), textAlign = TextAlign.Center)
            }
        }
    }
}
