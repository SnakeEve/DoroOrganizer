package com.doro.organizer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.session.GateState
import com.doro.organizer.session.showsContent
import com.doro.organizer.ui.gate.GateScreen
import com.doro.organizer.ui.gate.MaintenanceOverlay

/**
 * One screen per gate state (architecture.md § Rendering): the content only while `showsContent()`, every other state
 * through [GateScreen], which receives the graph as a [GateGraph] only (DEC-66, DEC-90). The content keeps its state
 * in the [DataEpochScope] of the current data epoch, which a data reset replaces (architecture.md § Data reset).
 */
@Composable
fun DoroRoot(graph: UiGraph = LocalUiGraph.current) {
    // Not lifecycle-aware on purpose: a background lock is in state before the first frame.
    val gate by graph.sessionGate.state.collectAsState()
    val settings = graph.settingsStore.settings.collectAsState()
    val dataEpoch by remember { derivedStateOf { settings.value?.dataEpoch } }
    val epochs = rememberDataEpochScope(dataEpoch) {
        // Results picked for the replaced data must not reach the new data (architecture.md § Result launchers).
        graph.externalActivities.take(ExternalKind.Backup)
        graph.externalActivities.take(ExternalKind.Restore)
        // Its id names a note of the replaced data (DEC-58, DEC-110).
        graph.newNoteMarker.clear()
    }
    if (gate.showsContent()) {
        val maintenance = gate as? GateState.Maintenance // origin Ready and not an Erase: showsContent implies it
        Box(Modifier.fillMaxSize()) {
            // Null only before the settings are read, while the gate is still Starting.
            dataEpoch?.let { epoch ->
                epochs.Content(epoch) {
                    // The container is always present and only its modifier changes, so MainContent keeps its single
                    // call site and all of its state. Pointer blocking alone is not enough: TalkBack, Switch Access
                    // and a hardware keyboard bypass it (ui.md § 10.6).
                    val blocked = if (maintenance != null) {
                        Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false }
                    } else {
                        Modifier
                    }
                    Box(Modifier.fillMaxSize().then(blocked)) { MainContent(graph, epoch) }
                }
            }
            if (maintenance != null) MaintenanceOverlay(maintenance.op, graph.dbMaintenance)
        }
    } else {
        GateScreen(gate, graph)
    }
}
