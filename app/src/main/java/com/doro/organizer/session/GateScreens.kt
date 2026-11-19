package com.doro.organizer.session

import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.security.LockScreen

/** What the gate shows, as far as prompting is concerned (architecture.md § Event wiring). */
fun lockScreenOf(s: GateState): LockScreen = when (s) {
    GateState.Ready -> LockScreen.Content
    is GateState.NeedsPassword -> LockScreen.Password
    is GateState.Locked -> {
        val resume = s.resume
        when {
            s.password != null -> LockScreen.Password
            // A running fingerprint reset counts as the unlock: progress screen, never a prompt (DEC-63).
            resume is Resume.Maintenance && resume.state.resetFrom != null -> LockScreen.Other
            resume == Resume.Open -> LockScreen.PromptToOpen
            resume == Resume.KeyUnavailable -> LockScreen.PromptWithoutKey
            // No key-state can check a key: a KeyUnavailable origin, or an Erase that may have deleted it.
            resume is Resume.Maintenance &&
                (resume.state.origin == Origin.KeyUnavailable || resume.state.op == MaintenanceOp.Erase) ->
                LockScreen.PromptWithoutKey
            else -> LockScreen.Prompt
        }
    }
    else -> LockScreen.Other
}

/**
 * Whether the content (`NavDisplay`) is composed (architecture.md § Rendering): `Ready`, and a `Maintenance` from
 * `Ready` behind its overlay, except an Erase, which closes Room and deletes the key-state.
 */
fun GateState.showsContent(): Boolean =
    this == GateState.Ready ||
        (this is GateState.Maintenance && origin == Origin.Ready && op != MaintenanceOp.Erase)
