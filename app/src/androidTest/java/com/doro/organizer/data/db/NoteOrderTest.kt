package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EpochDays
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.listedIds
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The canonical order of DEC-23 in `NoteDao.observeRows`: testing.md I-10 to I-13 (the I-11 visual order is a
 * Compose test). Timestamps come from [FakeAppTime]; T0 < T1 < T2 < … are its instants one minute apart. Notes are
 * checked and unchecked through the production guarded statements of `NoteDao` (DEC-22).
 */
@RunWith(AndroidJUnit4::class)
class NoteOrderTest {
    @get:Rule
    val databases = TestDatabases()

    private val time = FakeAppTime()
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows

    @Before
    fun createDatabase() {
        db = databases.create()
        rows = TestRows(db.openHelper.writableDatabase)
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun uncheckedTies() = runTest(timeout = 10.seconds) {
        val (t0, t1, t2, t3) = instants(4)
        val older = rows.note(createdAt = t0, updatedAt = t2)
        val tieLow = rows.note(createdAt = t1, updatedAt = t2)
        val tieHigh = rows.note(createdAt = t1, updatedAt = t2)
        val newest = rows.note(createdAt = t0, updatedAt = t3)

        // updated_at DESC, then created_at DESC, then id DESC
        assertEquals(listOf(newest, tieHigh, tieLow, older), listed())
    }

    @Test
    fun checkedGroup_oldestCheckFirst() = runTest(timeout = 10.seconds) {
        val (t0, t1, t2, t3, t4) = instants(5)
        val a = rows.note(createdAt = t0, updatedAt = t4) // the most recent update: checked_at decides, not updated_at
        val b = rows.note(createdAt = t0)
        val c = rows.note(createdAt = t0)
        val unchecked = rows.note(createdAt = t0)
        notes.check(a, t1)
        notes.check(b, t2)
        notes.check(c, t3)

        assertEquals(listOf(unchecked, a, b, c), listed())
    }

    @Test
    fun equalCheckedAt_fallsBackToIdDesc() = runTest(timeout = 10.seconds) {
        val (t0, t1) = instants(2)
        val first = rows.note(createdAt = t0, checkedAt = t1)
        val second = rows.note(createdAt = t0, checkedAt = t1)

        assertEquals(listOf(second, first), listed())
    }

    @Test
    fun uncheckedPrecedeChecked_whateverTheTimestamps() = runTest(timeout = 10.seconds) {
        val (t0, t1, t2) = instants(3)
        val recentlyChecked = rows.note(createdAt = t2, updatedAt = t2, checkedAt = t2)
        val oldUnchecked = rows.note(createdAt = t0, updatedAt = t0)
        val alsoOld = rows.note(createdAt = t0, updatedAt = t1)

        assertEquals(listOf(alsoOld, oldUnchecked, recentlyChecked), listed())
    }

    @Test
    fun date_neverChangesTheOrder() = runTest(timeout = 10.seconds) {
        val (t0, t1, t2) = instants(3)
        val dates = listOf(EpochDays.MAX, null, EpochDays.MIN, 20_774L)
        val byUpdate = listOf(t2, t1, t0, t0).zip(dates).map { (updated, date) ->
            rows.note(createdAt = t0, updatedAt = updated, date = date)
        }
        val sameDate = List(2) { rows.note(createdAt = t0, updatedAt = t0, date = 20_774L) }
        val checked = dates.map { rows.note(createdAt = t0, checkedAt = t1, date = it) }

        // The newest note has the latest date and an older one the earliest: a date sort would fail.
        val unchecked = listOf(byUpdate[0], byUpdate[1]) + listOf(sameDate[1], sameDate[0], byUpdate[3], byUpdate[2])
        assertEquals(unchecked + checked.reversed(), listed())
    }

    @Test
    fun toggleMoves() = runTest(timeout = 10.seconds) {
        val (t0, t1, t2, t3) = instants(4)
        val u1 = rows.note(createdAt = t0, updatedAt = t1)
        val u2 = rows.note(createdAt = t0, updatedAt = t2)
        val u3 = rows.note(createdAt = t0, updatedAt = t3)
        val c = rows.note(createdAt = t0, checkedAt = t1)
        assertEquals(listOf(u3, u2, u1, c), listed())

        notes.uncheck(c, now = next())
        assertEquals("uncheck moves to the top", listOf(c, u3, u2, u1), listed())

        notes.check(u2, now = next())
        assertEquals("check moves to the very bottom", listOf(c, u3, u1, u2), listed())

        notes.check(u3, now = next())
        assertEquals("the newest check is last", listOf(c, u1, u2, u3), listed())
    }

    private val notes: NoteDao get() = db.noteDao()

    private suspend fun listed(): List<Long> = notes.listedIds()

    /** [count] instants one minute apart, starting now; the clock ends one minute after the last. */
    private fun instants(count: Int): List<Long> = List(count) { next() }

    private fun next(): Long = time.nowMillis().also { time.advanceBy(Duration.ofMinutes(1)) }
}
