package com.doro.organizer.ui.notes

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-64: the primary a list's position belongs to, and where a new primary's list starts (DEC-110 (4), ui.md
 * § 3.11 rule 4).
 */
class PrimaryAnchorTest {
    private val alice = Primary.Entity(1, "Alice")
    private val bob = Primary.Entity(2, "Bob")

    @Test
    fun theFirstDecidedPrimary_ofAnAnchorWithoutARecord_isNoChange() {
        assertFalse(PrimaryAnchor().changedTo(alice))
        assertFalse(PrimaryAnchor().changedTo(Primary.Day(10)))
        assertFalse(PrimaryAnchor().changedTo(Primary.Range(10, 12)))
    }

    @Test
    fun loadingNoneTheSameIdAndARename_areNoChange() {
        val anchor = PrimaryAnchor().apply { changedTo(alice) }

        assertFalse(anchor.changedTo(Primary.Loading))
        assertFalse(anchor.changedTo(Primary.None))
        assertFalse(anchor.changedTo(alice))
        assertFalse("a rename keeps the id", anchor.changedTo(Primary.Entity(1, "Alicia")))
    }

    @Test
    fun anotherEntityDayOrRange_isAChange_once() {
        val people = PrimaryAnchor().apply { changedTo(alice) }
        val calendar = PrimaryAnchor().apply { changedTo(Primary.Day(10)) }

        assertTrue(people.changedTo(bob))
        assertFalse("recorded", people.changedTo(bob))
        assertTrue("another day", calendar.changedTo(Primary.Day(11)))
        assertTrue("a range", calendar.changedTo(Primary.Range(11, 14)))
        assertTrue("another range", calendar.changedTo(Primary.Range(11, 15)))
        assertTrue("Clear range: its first day", calendar.changedTo(Primary.Day(11)))
    }

    @Test
    fun afterThePickerBody_everyEntityIsAChange_theEarlierOneToo() {
        val anchor = PrimaryAnchor().apply { changedTo(alice) }

        anchor.pickerBody()

        assertTrue(anchor.changedTo(alice))
        anchor.pickerBody()
        assertTrue(anchor.changedTo(bob))
    }

    @Test
    fun thePickerBodyAtFirstStart_makesTheFirstChoiceAChange() {
        val anchor = PrimaryAnchor().apply { changedTo(Primary.Loading) }

        anchor.pickerBody()

        assertTrue(anchor.changedTo(alice))
    }

    @Test
    fun theSaverRoundTrip_keepsTheRecordedPrimary_andTheBody() {
        val alices = restored(PrimaryAnchor().apply { changedTo(alice) })
        val body = restored(PrimaryAnchor().apply { pickerBody() })
        val range = restored(PrimaryAnchor().apply { changedTo(Primary.Range(10, 12)) })

        assertFalse("the same primary keeps the restored position", alices.changedTo(alice))
        assertTrue("another one counts", restored(PrimaryAnchor().apply { changedTo(alice) }).changedTo(bob))
        assertTrue(body.changedTo(alice))
        assertFalse(range.changedTo(Primary.Range(10, 12)))
        assertTrue(range.changedTo(Primary.Range(10, 13)))
    }

    @Test
    fun anAnchorWithoutARecord_savesNothing() {
        assertNull(with(PrimaryAnchor.Saver) { scope.save(PrimaryAnchor()) })
    }

    @Test
    fun theStart_isTheTopWithoutHeaderItems_orWithARowFirst() {
        assertEquals(ScrollPosition(0, 0), PrimaryAnchor.startOf(headerItems = 0, firstVisibleIndex = 12))
        assertEquals(ScrollPosition(0, 0), PrimaryAnchor.startOf(headerItems = 2, firstVisibleIndex = 2))
        assertNull("the grid on screen", PrimaryAnchor.startOf(headerItems = 2, firstVisibleIndex = 0))
        assertNull("the selection header first", PrimaryAnchor.startOf(headerItems = 2, firstVisibleIndex = 1))
    }

    private val scope = SaverScope { true }

    private fun restored(anchor: PrimaryAnchor): PrimaryAnchor = with(PrimaryAnchor.Saver) {
        restore(checkNotNull(scope.save(anchor)))
    }.let(::checkNotNull)
}
