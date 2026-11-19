package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.GateTest
import com.doro.organizer.testing.corruptPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The gate half of L-12 (DEC-85): corruption at open and at runtime shows `Corrupt`; the file is kept. */
@RunWith(AndroidJUnit4::class)
class CorruptionTest : GateTest() {
    @Test
    fun plainPage2Overwritten_isCorruptAtOpen_andTheFileIsKept() = runBlocking {
        scene.plant(KeyMode.PLAIN)
        val live = scene.liveFile()
        corruptPage(live, 2)
        val length = live.length()
        scene.coldStart()
        scene.awaitGate(GateState.Corrupt)
        assertEquals(length, live.length())
    }

    @Test
    fun runtimeCorruption_inReady_showsCorrupt() = runBlocking {
        scene.plant(KeyMode.PLAIN)
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        scene.damageOpenPlainDatabase()
        val read = app.container.databaseHolder.withDb { it.openHelper.readableDatabase.query("SELECT count(*) FROM notes").use { c -> c.moveToFirst() } }
        assertTrue(read is AppResult.Err)
        scene.awaitGate(GateState.Corrupt) // T26, Room closed by E3
        assertTrue(scene.liveFile().exists())
    }

    @Test
    fun runtimeCorruption_behindTheLock_locksCorrupt() = runBlocking<Unit> {
        scene.plant(KeyMode.PLAIN, lock = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        scene.backgroundTrip() // no answer scripted: the prompt stays open over Locked(Ready)
        scene.awaitGate(GateState.Locked(Resume.Ready))
        scene.damageOpenPlainDatabase()
        app.container.databaseHolder.withDb { it.openHelper.readableDatabase.query("SELECT count(*) FROM notes").use { c -> c.moveToFirst() } }
        scene.awaitGate(GateState.Locked(Resume.Corrupt)) // T13
    }
}
