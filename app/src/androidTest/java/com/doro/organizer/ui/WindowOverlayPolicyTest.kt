package com.doro.organizer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.assertFocusedDialogWindow
import com.doro.organizer.testing.dialogCatalog
import com.doro.organizer.testing.showDialog
import com.doro.organizer.testing.visitShellDialogs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-29, Compose half (security.md § 9.4, DEC-66): every security dialog hides overlay windows on its own
 * window through `SecureDialogWindow(hideOverlays = true)` on API 31+, the typed Erase confirmation among them, while
 * the other dialogs, the shell's included, leave them allowed; all of them keep autofill out (security.md § 11). The
 * window policy half is the JVM `WindowOverlayPolicyTest`; the list's and the editor's windows are in
 * `InputPolicyTest` and `ListInputUiTest`.
 */
@RunWith(AndroidJUnit4::class)
class WindowOverlayPolicyTest : ScriptedGateComposeTest() {
    @Test
    fun securityDialogs_hideOverlays_onTheirOwnWindow() = assertCatalog(security = true)

    @Test
    fun otherDialogs_leaveOverlaysAllowed() = assertCatalog(security = false)

    @Test
    fun shellDialogs_leaveOverlaysAllowed() {
        scene.launch(GateState.Ready)
        ui.awaitShell()

        visitShellDialogs { name -> assertFocusedDialogWindow(hidesOverlays = false, name) }
    }

    private fun assertCatalog(security: Boolean) {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        val dialogs = dialogCatalog(scene.time, app.container.dispatchers).filter { it.security == security }
        for (dialog in dialogs) {
            showDialog(dialog.content)
            assertFocusedDialogWindow(hidesOverlays = security, dialog.name)
        }
    }
}
