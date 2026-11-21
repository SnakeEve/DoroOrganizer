package com.doro.organizer.ui.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.ListComposeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-11, the visual half (DEC-23): the list shows the rows in the query's order, read as rendered. A UI that
 * regrouped or re-sorted them would fail.
 */
@RunWith(AndroidJUnit4::class)
class NoteListOrderUiTest : ListComposeTest() {
    @Test
    fun checkedGroup_visualOrder() {
        var u = 0L
        var a = 0L
        var b = 0L
        var c = 0L
        startWith {
            // Created newest first, so a UI that sorted by creation or id would put C, B, A above U.
            a = note("A", createdAt = now, checkedAt = now + 1_000)
            b = note("B", createdAt = now + 1, checkedAt = now + 2_000)
            c = note("C", createdAt = now + 2, checkedAt = now + 3_000)
            u = note("U", createdAt = now - 1)
        }
        awaitRow(c)

        assertEquals(listOf(u, a, b, c), onScreenOrder(c, b, a, u))
    }
}
