package com.doro.organizer.data.maintenance

import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table and the data-reset marker rule of db-lifecycle.md § Startup reconciliation as pure functions
 * (the file side is `StartupReconciliationTest`, testing.md L-16).
 */
class StartupRulesTest {
    private fun valid(generation: Long, mode: KeyMode = KeyMode.DEVICE) =
        KeyStateRead.Valid(KeyState(generation, KeySlot(mode, null, null, null, null)))

    private val allPlain: (Long) -> Boolean = { true }

    @Test
    fun aValidKeyState_proceedsWithItsGenerationAndMode_whateverFilesExist() {
        assertEquals(StartupDecision.Proceed(4, KeyMode.DEVICE), startupDecision(valid(4), setOf(3, 4), allPlain))
        assertEquals(StartupDecision.Proceed(4, KeyMode.DEVICE), startupDecision(valid(4), emptySet(), allPlain))
    }

    @Test
    fun aMissingKeyState_isFirstRun_adoption_orKeyUnavailable_byTheMainFiles() {
        assertEquals(StartupDecision.FirstRun, startupDecision(KeyStateRead.Missing, emptySet(), allPlain))
        assertEquals(StartupDecision.AdoptPlain(9), startupDecision(KeyStateRead.Missing, setOf(2, 9, 5), allPlain))
        assertEquals(StartupDecision.KeyUnavailable, startupDecision(KeyStateRead.Missing, setOf(2, 9)) { it == 2L })
    }

    @Test
    fun anInvalidKeyState_isKeyUnavailable_evenWithPlaintextFilesOrNone() {
        assertEquals(StartupDecision.KeyUnavailable, startupDecision(KeyStateRead.Invalid, emptySet(), allPlain))
        assertEquals(StartupDecision.KeyUnavailable, startupDecision(KeyStateRead.Invalid, setOf(1), allPlain))
    }

    @Test
    fun onlyAValidKeyStateWithAUsableLiveFile_deletesTheOtherSets() {
        assertEquals(setOf(1L, 3L), unreferencedAtStartup(valid(2), setOf(1, 2, 3), liveFileUsable = true))
        assertEquals(emptySet<Long>(), unreferencedAtStartup(valid(2), setOf(1, 3), liveFileUsable = false))
        assertEquals(emptySet<Long>(), unreferencedAtStartup(KeyStateRead.Missing, setOf(1, 2), liveFileUsable = true))
        assertEquals(emptySet<Long>(), unreferencedAtStartup(KeyStateRead.Invalid, setOf(1, 2), liveFileUsable = true))
    }

    @Test
    fun theMarker_resetsExactlyWhenItsGenerationWasCommitted_orWhenUnparseable() {
        assertTrue(markerResets(valid(7), "7"))
        assertTrue(markerResets(valid(7), "7\n"))
        assertFalse(markerResets(valid(7), "6"))
        assertFalse(markerResets(KeyStateRead.Missing, "7"))
        assertFalse(markerResets(KeyStateRead.Invalid, "7"))
        assertTrue(markerResets(valid(7), "seven"))
        assertTrue(markerResets(KeyStateRead.Missing, ""))
    }
}
