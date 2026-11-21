package com.doro.organizer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The saved form of the Settings dialog (ui.md § 9.8): every dialog survives the String key, unknown keys open none. */
class SettingsDialogKeyTest {
    private val every = listOf(
        SettingsDialog.PlainBackupWarning(share = false),
        SettingsDialog.PlainBackupWarning(share = true),
        SettingsDialog.BackupPassword(share = false),
        SettingsDialog.BackupPassword(share = true),
        SettingsDialog.BackupOffer,
        SettingsDialog.LockOnPassword,
        SettingsDialog.SlotSetupPassword,
        SettingsDialog.LockOffPassword,
        SettingsDialog.Notices,
    ) + KeyFlow.entries.map(SettingsDialog::KeyChange)

    @Test
    fun everyDialog_survivesItsKey() {
        for (dialog in every) assertEquals(dialog, settingsDialogOf(dialog.toKey()))
        assertEquals("distinct keys", every.size, every.map { it.toKey() }.toSet().size)
    }

    @Test
    fun aMissingOrUnknownKey_opensNoDialog() {
        for (key in listOf(null, "", "Gone", "KeyChange:Gone", "BackupPassword:", "PlainBackupWarning:elsewhere")) {
            assertNull(key, settingsDialogOf(key))
        }
    }

    @Test
    fun keysHoldNoFieldContent() {
        assertEquals("KeyChange:SetPassword", SettingsDialog.KeyChange(KeyFlow.SetPassword).toKey())
        assertEquals("BackupPassword:share", SettingsDialog.BackupPassword(share = true).toKey())
    }
}
