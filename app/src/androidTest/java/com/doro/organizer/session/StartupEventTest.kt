package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.GateTest
import com.doro.organizer.testing.TestKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The E1-event half of L-16: what the gate does with each kind of `StartupDecision` (architecture.md E1 step 6):
 * `FirstRun` and `AdoptPlain` load as `PLAIN`, `Proceed` loads its mode, `KeyUnavailable` is `StartupKeyFailed`.
 */
@RunWith(AndroidJUnit4::class)
class StartupEventTest : GateTest() {
    @Test
    fun firstRun_loadsPlain_andOpensTheNewDatabase() = runBlocking {
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        assertEquals(KeyMode.PLAIN, app.container.keyVault.readState()?.slot?.mode)
    }

    @Test
    fun adoptPlain_loadsPlain_andOpensTheAdoptedFile() = runBlocking {
        databases.createLive(GENERATION, TestKey.Plain)
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        assertEquals(GENERATION, app.container.keyVault.readState()?.generation)
        assertEquals(AppDatabase.VERSION, scene.liveFile().let { databases.raw(it, TestKey.Plain).use { db -> db.version } })
    }

    @Test
    fun proceed_loadsTheCommittedMode() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, "correct horse")
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword } // T2: only a PASSWORD load gives the password screen
    }

    @Test
    fun keyUnavailable_isStartupKeyFailed() = runBlocking<Unit> {
        scene.plant(KeyMode.DEVICE)
        vaults.stateFile.writeText("not json")
        scene.coldStart()
        scene.awaitGate(GateState.KeyUnavailable) // T6
    }

    private companion object {
        const val GENERATION = 4L
    }
}
