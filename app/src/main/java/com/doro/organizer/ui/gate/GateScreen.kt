package com.doro.organizer.ui.gate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.session.GateEvent
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Resume
import com.doro.organizer.ui.GateGraph
import com.doro.organizer.ui.common.MaintenanceStatusHost
import com.doro.organizer.ui.common.rememberAnnouncer

/**
 * Every gate state except the content (architecture.md § Rendering, ui.md § 10), from the gate state and the
 * [GateGraph] only: no note-derived data (DEC-66). Which password screen shows the Forgot password screen is the one
 * piece of local state; it is saveable and outside the per-state branches, so a failed erase returns to it (ui.md
 * § 10.3). The password and Forgot password screens compose the maintenance status host, which runs in lock mode over
 * a `Locked` gate (ui.md § 8.12); the recovery screens compose their own.
 */
@Composable
fun GateScreen(gate: GateState, graph: GateGraph) {
    var forgotOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(gate) { if (gate.closesForgotScreen()) forgotOpen = false }
    val eraseOrigin = eraseOriginOf(gate)
    val password = passwordScreenOf(gate)
    when {
        forgotOpen && eraseOrigin != null -> ForgotPasswordScreen(
            eraseOrigin,
            resetOriginOf(gate),
            // The activity ViewModelStore: the prompt and the reset call survive a rotation (ui.md § 8.15, DEC-90).
            viewModel(factory = forgotPasswordFactory(graph)),
            graph,
            onClose = { forgotOpen = false },
        )
        password != null -> PasswordScreen(
            password,
            graph.appTime,
            rememberAnnouncer(graph),
            onSubmit = { graph.sessionGate.dispatch(GateEvent.PasswordSubmitted(it)) },
            onUseFingerprint = { graph.sessionGate.dispatch(GateEvent.UseFingerprintChosen) },
            onForgot = { forgotOpen = true },
        )
        else -> OtherGateScreen(gate, graph)
    }
    // One call site for both screens, so switching between them keeps the host's state.
    if ((forgotOpen && eraseOrigin != null) || password != null) MaintenanceStatusHost(graph)
}

@Composable
private fun OtherGateScreen(gate: GateState, graph: GateGraph) {
    when (gate) {
        is GateState.Locked -> {
            val resume = gate.resume
            // A running fingerprint reset counts as the unlock: its progress, never the lock screen (DEC-63).
            if (resume is Resume.Maintenance && resume.state.resetFrom != null) {
                MaintenanceScreen(MaintenanceOp.KeyChange, graph.dbMaintenance)
            } else {
                LockedScreen(gate.authError, graph.appLockController)
            }
        }
        is GateState.Opening ->
            if (gate.transientError) {
                OpenErrorScreen(onRetry = { graph.sessionGate.dispatch(GateEvent.Retry) })
            } else {
                OpeningScreen()
            }
        is GateState.Maintenance -> MaintenanceScreen(gate.op, graph.dbMaintenance)
        GateState.Corrupt -> RecoveryScreen(Recovery.Corrupt, graph)
        GateState.KeyUnavailable -> RecoveryScreen(Recovery.KeyUnavailable, graph)
        // NeedsPassword is a password screen; Ready is the content, never a gate screen.
        is GateState.Starting, is GateState.NeedsPassword, GateState.Ready -> BlankScreen()
    }
}
