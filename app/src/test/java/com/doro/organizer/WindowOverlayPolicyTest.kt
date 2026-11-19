package com.doro.organizer

import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.WindowPolicy
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.Resume
import com.doro.organizer.session.windowPolicyOf
import com.doro.organizer.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** L-29 (JVM half) and the window-policy rules of security.md § 8 (DEC-66, DEC-67). */
class WindowOverlayPolicyTest {
    private val settings = Settings()

    @Test
    fun overlays_areHidden_inEveryGateStateExceptTheContent() {
        val hidden = listOf(
            GateState.Starting(false), GateState.NeedsPassword(0, 0, false), GateState.Locked(Resume.Ready),
            GateState.Opening(viaPassword = false, lockPending = false, transientError = false), GateState.Corrupt,
            GateState.KeyUnavailable, GateState.Maintenance(MaintenanceOp.Restore, Origin.Corrupt),
            GateState.Maintenance(MaintenanceOp.StartFresh, Origin.KeyUnavailable),
            GateState.Maintenance(MaintenanceOp.Erase, Origin.NeedsPassword),
        )
        hidden.forEach { assertEquals("$it", true, windowPolicyOf(settings, KeyMode.PLAIN, it).hideOverlays) }
        val shown = listOf(
            GateState.Ready, GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready),
            GateState.Maintenance(MaintenanceOp.Erase, Origin.Ready), // DEC-66: an Erase from Ready included
        )
        shown.forEach { assertEquals("$it", false, windowPolicyOf(settings, KeyMode.PLAIN, it).hideOverlays) }
    }

    @Test
    fun unknownSettingsOrKeyMode_isTheClosedPolicy() {
        assertEquals(WindowPolicy.Closed, windowPolicyOf(null, KeyMode.PLAIN, GateState.Ready))
        assertEquals(WindowPolicy.Closed, windowPolicyOf(settings, null, GateState.Ready))
    }

    @Test
    fun flagSecure_followsScreenshotProtection_andRecentsFollowLockAndEncryption() {
        assertEquals(true, windowPolicyOf(settings.copy(screenshotProtection = true), KeyMode.PLAIN, GateState.Ready).secure)
        assertEquals(false, windowPolicyOf(settings, KeyMode.PLAIN, GateState.Ready).secure)
        assertEquals(true, windowPolicyOf(settings, KeyMode.PLAIN, GateState.Ready).recentsScreenshot)
        assertEquals(false, windowPolicyOf(settings.copy(biometricLock = true), KeyMode.PLAIN, GateState.Ready).recentsScreenshot)
        assertEquals(false, windowPolicyOf(settings, KeyMode.DEVICE, GateState.Ready).recentsScreenshot)
        assertEquals(false, windowPolicyOf(settings, KeyMode.PASSWORD, GateState.Ready).recentsScreenshot)
    }

    @Test
    fun applier_requestsOverlayHidingOnlyOnApi31Plus_andRecentsOnlyOnApi33Plus() {
        val policy = WindowPolicy(secure = true, recentsScreenshot = false, hideOverlays = true)
        for ((sdk, expected) in listOf(
            28 to listOf("secure=true"),
            30 to listOf("secure=true"),
            31 to listOf("secure=true", "hideOverlays=true"),
            33 to listOf("secure=true", "recents=false", "hideOverlays=true"),
        )) {
            val window = RecordingWindow()
            applyWindowPolicy(policy, window, sdk)
            assertEquals("API $sdk", expected, window.calls)
        }
    }

    private class RecordingWindow : PolicyWindow {
        val calls = mutableListOf<String>()

        override fun setSecure(secure: Boolean) {
            calls += "secure=$secure"
        }

        override fun setRecentsScreenshotEnabled(enabled: Boolean) {
            calls += "recents=$enabled"
        }

        override fun setHideOverlayWindows(hide: Boolean) {
            calls += "hideOverlays=$hide"
        }
    }
}
