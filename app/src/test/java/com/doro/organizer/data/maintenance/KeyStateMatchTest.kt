package com.doro.organizer.data.maintenance

import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.Kdf
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.testing.TEST_SLOT_KEY_ALIAS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read-back test of every key-state commit (db-lifecycle.md § Generation commit): byte equality, never `==`. */
class KeyStateMatchTest {
    private fun slot(iterations: Int = 600_000, salt: Byte = 1) =
        KeySlot(KeyMode.PASSWORD, ByteArray(12) { 2 }, ByteArray(60) { 3 }, ByteArray(12) { 4 }, Kdf(iterations, ByteArray(16) { salt }))

    private fun bio(fill: Byte = 5) = BioSlot(TEST_SLOT_KEY_ALIAS, ByteArray(12) { fill }, ByteArray(48) { 6 })

    @Test
    fun equalGenerationSlotAndBioSlot_match_byContentNotIdentity() {
        assertTrue(KeyState(3, slot(), bio()).matches(3, slot(), bio()))
        assertTrue(KeyState(3, slot()).matches(3, slot(), null))
    }

    @Test
    fun anyDifference_isNoMatch() {
        assertFalse(KeyState(3, slot()).matches(4, slot(), null))
        assertFalse(KeyState(3, slot(iterations = 600_001)).matches(3, slot(), null))
        assertFalse(KeyState(3, slot(salt = 9)).matches(3, slot(), null))
        assertFalse(KeyState(3, slot(), bio(fill = 7)).matches(3, slot(), bio()))
    }

    @Test
    fun theBioSlot_mustBeExactlyWhatWasWritten() {
        assertFalse(bioMatches(actual = bio(), expected = null)) // a slot left where none was written
        assertFalse(bioMatches(actual = null, expected = bio())) // the slot did not land, or reads back dead
        assertTrue(bioMatches(actual = null, expected = null))
    }
}
