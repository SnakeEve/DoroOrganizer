package com.doro.organizer.session

import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.WindowPolicy
import com.doro.organizer.settings.Settings

/**
 * The window policy of [settings], the committed key [mode] and the [gate] state (architecture.md § Dependency
 * injection, security.md § 8, § 9.4). Closed while the settings have not been read or the mode is unknown. Overlays
 * are allowed only over the content: `Ready` and a `Maintenance` from `Ready` (an Erase included, DEC-66).
 */
fun windowPolicyOf(settings: Settings?, mode: KeyMode?, gate: GateState): WindowPolicy =
    if (settings == null || mode == null) {
        WindowPolicy.Closed
    } else {
        WindowPolicy(
            secure = settings.screenshotProtection,
            recentsScreenshot = !(settings.biometricLock || mode != KeyMode.PLAIN),
            hideOverlays = !(gate == GateState.Ready || (gate as? GateState.Maintenance)?.origin == Origin.Ready),
        )
    }
