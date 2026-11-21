package com.doro.organizer.ui.notes

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-46 (§13.5, DEC-54, ui.md § 3.9): the checkbox moves its row to the very bottom and back to the top, its
 * description names the action it takes, and each change is announced once.
 */
@RunWith(AndroidJUnit4::class)
class ToggleUiTest : ListComposeTest() {
    @Test
    fun check_movesTheRowToTheBottom_andUncheck_backToTheTop() {
        var first = 0L
        var second = 0L
        var done = 0L
        startWith {
            done = note("done", createdAt = now - 3, checkedAt = now - 1)
            second = note("second", createdAt = now - 2)
            first = note("first", createdAt = now - 1)
        }
        awaitRow(first)
        assertEquals(listOf(first, second, done), onScreenOrder(first, second, done))
        assertEquals(MARK_DONE, checkboxDescription(first))

        compose.onNodeWithTag(checkboxTag(first), useUnmergedTree = true).performClick()

        awaitDescription(first, MARK_NOT_DONE)
        compose.waitForIdle()
        assertEquals(listOf(second, done, first), onScreenOrder(first, second, done))
        assertEquals(1, scene.announcements.count(MARKED_DONE))

        compose.onNodeWithTag(checkboxTag(first), useUnmergedTree = true).performClick()

        awaitDescription(first, MARK_DONE)
        compose.waitForIdle()
        assertEquals(listOf(first, second, done), onScreenOrder(first, second, done))
        assertEquals(1, scene.announcements.count(MARKED_NOT_DONE))
    }

    private fun awaitDescription(id: Long, description: String) =
        compose.waitUntil(GateUi.TIMEOUT_MS) { checkboxDescription(id) == description }
}
