package com.doro.organizer.ui.notes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.ScriptedGateComposeTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * testing.md I-33 (DEC-25, Q4; ui.md § 3.6): "(No data)" exactly when the fully filtered query has no rows, never
 * before its first emission, never for a blank note, and never instead of the picker of Individual and Project
 * (DEC-52). The editor's hint for a blank note comes with the editor (SPEC T18).
 */
@RunWith(Enclosed::class)
class EmptyStateUiTest {
    @RunWith(AndroidJUnit4::class)
    class Lists : ListComposeTest() {
        @Test
        fun emptyDatabase_showsNoData_inAll_Orphans_andCalendar() {
            startWith()

            for (p in listOf(Perspective.All, Perspective.Orphan, Perspective.Date)) {
                openTab(p)
                ui.awaitText(NO_DATA)
                compose.onAllNodesWithText(NO_DATA).assertCountEquals(1)
                compose.onNodeWithText(CLEAR_FILTERS).assertDoesNotExist()
            }
        }

        @Test
        fun entityWithoutNotes_showsNoData() {
            var person = 0L
            startWith(settings = { setRemembered(EntityKind.Person, person) }) {
                person = person("Alice", now)
            }

            openTab(Perspective.Individual)

            ui.awaitText(NO_DATA)
            compose.onNodeWithTag(PICKER_SEARCH).assertDoesNotExist()
        }

        @Test
        fun emptyDay_showsNoData_underTheCalendar() {
            startWith { note("tomorrow", createdAt = now, date = today + 1) }

            openTab(Perspective.Date)

            ui.awaitText(NO_DATA)
            val grid = ui.boundsOf(MONTH_GRID)
            assertTrue("top-aligned under the grid", compose.onNodeWithText(NO_DATA).fetchSemanticsNode().boundsInRoot.top > grid.bottom)
        }

        @Test
        fun allNotesLinked_orphansShowNoData() {
            startWith {
                val note = note("linked", createdAt = now)
                linkProject(note, project("Alpha", now))
            }

            openTab(Perspective.Orphan)

            ui.awaitText(NO_DATA)
        }

        @Test
        fun searchMiss_showsNoData_andClearFiltersBringsTheRowsBack() {
            var note = 0L
            startWith { note = note("alpha", createdAt = now) }
            awaitRow(note)

            compose.onNodeWithContentDescription(SEARCH).performClick()
            compose.onNodeWithTag(SEARCH_FIELD).performTextInput("zzz")

            ui.awaitText(NO_DATA)
            awaitNoRow(note)
            compose.onNodeWithText(CLEAR_FILTERS).performClick()

            awaitRow(note)
            compose.onNodeWithTag(SEARCH_FIELD).assertDoesNotExist()
            compose.onNodeWithText(NO_DATA).assertDoesNotExist()
        }

        @Test
        fun individualAndProject_withoutASelection_showThePicker_notNoData() {
            var person = 0L
            var project = 0L
            startWith {
                person = person("Alice", now)
                project = project("Alpha", now)
            }

            openTab(Perspective.Individual)
            ui.awaitTag("picker_row_$person")
            compose.onNodeWithText(NO_DATA).assertDoesNotExist()

            openTab(Perspective.Project)
            ui.awaitTag("picker_row_$project")
            compose.onNodeWithText(NO_DATA).assertDoesNotExist()
        }

        @Test
        fun blankNoteListedWithPlaceholder() {
            var empty = 0L
            var whitespace = 0L
            startWith {
                empty = note("", createdAt = now)
                whitespace = note(" \n\t　", createdAt = now + 1)
            }

            awaitRow(empty)
            awaitRow(whitespace)

            assertTrue(rowDescription(empty).startsWith(NO_DATA))
            assertTrue(rowDescription(whitespace).startsWith(NO_DATA))
            // The placeholder is the rows' title, not the empty state's text.
            compose.onAllNodesWithText(NO_DATA).assertCountEquals(0)
        }
    }

    /** Before the first emission, from a scripted gate whose database never opens: loading, never "(No data)". */
    @RunWith(AndroidJUnit4::class)
    class BeforeTheFirstEmission : ScriptedGateComposeTest() {
        @Test
        fun theLoadingStateShows_andNoNoData() {
            scene.launch(GateState.Ready)
            ui.awaitShell()

            compose.waitUntil(GateUi.TIMEOUT_MS) {
                compose.onAllNodes(ListComposeTest.describedWith(LOADING)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(ListComposeTest.NO_DATA).assertCountEquals(0)
            compose.onAllNodesWithTag("note_list").assertCountEquals(0)
        }
    }

    private companion object {
        const val SEARCH_FIELD = "search_field"
        const val PICKER_SEARCH = "picker_search"
        const val MONTH_GRID = "month_grid"

        /** `a11y_loading`, ui.md § 11.1. */
        const val LOADING = "Loading"
    }
}
