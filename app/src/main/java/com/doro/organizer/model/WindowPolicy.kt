package com.doro.organizer.model

/**
 * The window flags (DEC-66, DEC-67, security.md § 8): FLAG_SECURE, the Recents screenshot (API 33+) and overlay
 * hiding (API 31+). Keyboard privacy is not part of it: it is unconditional (DEC-71).
 */
data class WindowPolicy(val secure: Boolean, val recentsScreenshot: Boolean, val hideOverlays: Boolean) {
    override fun toString() = "WindowPolicy(secure=$secure, recentsScreenshot=$recentsScreenshot, hideOverlays=$hideOverlays)"

    companion object {
        /** While the settings or the key mode are unknown: everything protected. */
        val Closed = WindowPolicy(secure = true, recentsScreenshot = false, hideOverlays = true)
    }
}
