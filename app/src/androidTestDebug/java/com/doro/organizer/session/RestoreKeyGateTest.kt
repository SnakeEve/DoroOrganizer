package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.testing.CorruptRestoreScene
import com.doro.organizer.testing.GateTest
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer
import com.doro.organizer.testing.RestoreAnswer.Companion.backupPassword
import com.doro.organizer.testing.RestoreAnswer.Companion.confirm
import com.doro.organizer.testing.RestoreAnswer.Companion.databasePassword
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.restoreAnswering
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate halves of L-43 and L-51 (DEC-84, DEC-86): a restore from `Corrupt` in `PASSWORD` mode run by the real
 * `DbMaintenance` and the real `AppLockController`, with this test in the role of `MaintenanceStatusHost`, which
 * answers each fingerprint question through `slotPrompt(RestoreKey)` and reports a Keystore-layer failure of the
 * password through `reportPasswordKekFailure()`. The `kekBroken` flag then puts the slot before the due reminder, both
 * for the restore's fallback and for the start after its `Restart`.
 */
@RunWith(AndroidJUnit4::class)
class RestoreKeyGateTest : GateTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lock get() = app.container.appLockController

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    /** The status host's answer to a fingerprint question: the slot prompt of `RestoreKey`, then its cipher or null. */
    private fun fingerprintThroughTheController(afterKekFailure: Boolean, before: suspend () -> Unit = {}) =
        RestoreAnswer("fingerprint (afterKekFailure=$afterKekFailure)", { it == MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure) }) {
            before()
            val cipher: SlotCipher? = (lock.slotPrompt(AuthPurpose.RestoreKey) as AppResult.Ok).value
            answerBiometric(cipher)
        }

    @Test
    fun aKekFailure_asksTheFingerprintOnceMore_andTheStartAfterTheRestartUsesTheSlot_notTheReminder() = runBlocking {
        val setup = MaintenanceSetup(databases, vaults, scope.scope, scene.settings, scene.time, scene.kek, scene.bio)
        val restore = CorruptRestoreScene(setup, vaults, context)
        restore.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD) // lock on, a live slot, a damaged file
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Corrupt) // through the slot prompt (T40, T42, T17)
        assertEquals(1, scene.gateway.biometricPromptCount)
        scene.time.advanceBy(Duration.ofDays(15)) // the reminder is due from here on
        vaults.deleteKekAlias()

        val result = app.container.dbMaintenance.restoreAnswering(
            restore.backup.uri,
            MaintenanceOrigin.Corrupt,
            backupPassword(BACKUP_PASSWORD),
            fingerprintThroughTheController(afterKekFailure = false), // the due reminder blocks it: no prompt
            databasePassword(DATABASE_PASSWORD), // passes the GCM tag, but the KEK is gone
            fingerprintThroughTheController(afterKekFailure = true) {
                assertTrue("a live slot can replace the password", lock.reportPasswordKekFailure())
                scene.gateway.succeed()
            },
            confirm {
                assertEquals("exactly one further prompt (L-51 a)", 2, scene.gateway.biometricPromptCount)
                scene.gateway.succeed() // for the start after the Restart, which may prompt before restore() returns
            },
        )

        assertTrue("$result", (result as AppResult.Ok).value is MaintenanceResult.Restored)
        // The Restart (T30) starts over in this process: the slot route despite the due reminder (L-51 b, L-43).
        scene.awaitGate(GateState.Ready)
        assertEquals(3, scene.gateway.biometricPromptCount)
        assertTrue(scene.gateway.prompts.last().withCrypto)
        assertTrue("the notice waits for Ready", lock.kekNotice.value)
    }

    private companion object {
        const val DATABASE_PASSWORD = "database password"
        const val BACKUP_PASSWORD = "backup password"
    }
}
