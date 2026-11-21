package com.doro.organizer.ui.notes

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.closeKeyboard
import com.doro.organizer.testing.pickerRowTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-21, Compose half (DEC-36, DEC-54; ui.md § 3.8, § 5): long-press starts selection mode, a row tap toggles
 * the selection and the done checkbox never does; Select all takes the full current query; the snackbar counts the
 * selected notes in plural form; the selection ends after an action, on Back (before the search, which returns without
 * focus) and on a tab change, whose returning tab never shows it; selection mode locks
 * the filters and offers no batch check; Associate links the selection to the entities picked and created on both
 * tabs of its picker in one call, and Back from that picker keeps the selection and writes nothing; that picker,
 * restored after Android closed the app, stays with a saved selection and closes without one (DEC-57).
 */
@RunWith(AndroidJUnit4::class)
class BatchUiTest : ListComposeTest() {
    @Test
    fun longPressStartsSelection_aTapToggles_andTheCheckboxNeverSelects() {
        var a = 0L
        var b = 0L
        var c = 0L
        startWith {
            a = note("a", createdAt = now - 1)
            b = note("b", createdAt = now - 2)
            c = note("c", createdAt = now - 3)
        }
        awaitRow(a)
        assertNull(rowState(a))

        longPress(a)
        ui.awaitText(selectedTitle(1))
        assertEquals(SELECTED, rowState(a))
        assertEquals(NOT_SELECTED, rowState(b))

        compose.onNodeWithTag(rowTag(b)).performClick()
        ui.awaitText(selectedTitle(2))

        compose.onNodeWithTag(checkboxTag(c), useUnmergedTree = true).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { rowState(c) == "$NOT_SELECTED, Done" }
        assertEquals(1, ui.countText(selectedTitle(2)))

        compose.onNodeWithTag(rowTag(a)).performClick()
        ui.awaitText(selectedTitle(1))
        longPress(b) // in selection mode a long-press is a tap: deselecting the last row ends the mode
        awaitNoSelection()
        assertNull(rowState(a))
        assertEquals("Done", rowState(c))
    }

    @Test
    fun selectAll_takesTheWholeQuery_withTheSearch_checkedNotes_andOffScreenRows() {
        val alpha = mutableListOf<Long>()
        var beta = 0L
        startWith {
            repeat(40) { i -> alpha += note("alpha $i", createdAt = now - 100 + i, checkedAt = (now + i).takeIf { i % 4 == 0 }) }
            beta = note("beta", createdAt = now)
        }
        awaitRow(beta)
        compose.onNodeWithContentDescription(SEARCH).performClick()
        compose.onNodeWithTag("search_field").performTextInput("alpha")
        awaitNoRow(beta)

        longPress(alpha.last()) // the newest unchecked alpha: the top row
        ui.awaitText(selectedTitle(1))
        tapAction(SELECT_ALL)

        ui.awaitText(selectedTitle(40))
    }

    @Test
    fun setDate_andRemoveDate_countTheSelectedNotes_andEndSelectionMode() {
        var a = 0L
        var b = 0L
        startWith {
            a = note("a", createdAt = now - 1, date = today)
            b = note("b", createdAt = now - 2, date = today)
        }
        awaitRow(a)
        longPress(a)
        compose.onNodeWithTag(rowTag(b)).performClick()
        ui.awaitText(selectedTitle(2))

        tapAction(MORE_OPTIONS)
        compose.onNodeWithText(SET_DATE).performClick()
        ui.awaitText("Set date")
        // Both notes share today, so the picker starts on it and OK is enabled at once (ui.md § 5.3).
        compose.onNodeWithText("OK").assertIsEnabled().performClick()
        ui.awaitText("Date set on 2 notes")
        awaitNoSelection()

        longPress(b)
        tapAction(MORE_OPTIONS)
        compose.onNodeWithText(SET_DATE).performClick()
        compose.onNodeWithText("Remove date").performClick()
        ui.awaitText("Date removed from 1 note")
        awaitNoSelection()
    }

    @Test
    fun backAndAnotherTab_endSelectionMode_butRotationKeepsIt() {
        var a = 0L
        startWith { a = note("a", createdAt = now) }
        awaitRow(a)

        longPress(a)
        ui.awaitText(selectedTitle(1))
        scene.recreateActivity()
        ui.awaitText(selectedTitle(1))

        ui.pressBack()
        awaitNoSelection()
        compose.onNodeWithTag("tab_All").assertExists()
        assertNull(rowState(a))

        longPress(a)
        ui.awaitText(selectedTitle(1))
        openTab(Perspective.Orphan)
        // Frame by frame: the returning tab never shows the contextual bar, not even before onTabLeft runs.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("tab_All").performClick()
        repeat(RETURN_FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val bars = compose.onAllNodesWithContentDescription(EXIT_SELECTION).fetchSemanticsNodes()
            assertTrue("contextual bar in frame $frame", bars.isEmpty())
        }
        compose.mainClock.autoAdvance = true
        awaitRow(a)
        awaitNoSelection()
        assertNull(rowState(a))
    }

    @Test
    fun back_endsSelectionModeBeforeTheSearch_andTheSearchReturnsWithoutFocus() {
        var a = 0L
        startWith { a = note("alpha", createdAt = now) }
        awaitRow(a)
        compose.onNodeWithContentDescription(SEARCH).performClick()
        compose.onNodeWithTag("search_field").performTextInput("alpha")
        compose.onNodeWithTag("search_field").performImeAction() // applies the search and hides the keyboard
        compose.waitForIdle()

        longPress(a)
        ui.awaitText(selectedTitle(1))
        ui.pressBack()
        awaitNoSelection()

        // ui.md § 2.5 and § 3.3: the search bar is back with its text, without focus or keyboard.
        compose.onNodeWithTag("search_field").assertTextContains("alpha").assertIsNotFocused()
        awaitRow(a)
        ui.pressBack()
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithTag("search_field").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun selectionMode_locksTheCalendarAndTheChips_andOffersNoBatchCheck() {
        var a = 0L
        startWith { a = note("a", createdAt = now, date = today) }
        openTab(Perspective.Date)
        awaitRow(a)

        longPress(a)
        ui.awaitText(selectedTitle(1))

        compose.onNodeWithTag(dayTag(today + 1)).assertIsNotEnabled()
        compose.onNodeWithTag("chip_Person").assertIsNotEnabled()
        compose.onNodeWithTag("chip_Project").assertIsNotEnabled()
        compose.onNodeWithText("Select range").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next month").assertIsEnabled()

        tapAction(MORE_OPTIONS)
        val items = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes()
        assertEquals(2, items.size)
        assertTrue(compose.onAllNodesWithText(SET_DATE).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText(DELETE).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun associate_linksTheSelection_toPickedAndCreatedEntities_inOneCall() {
        var a = 0L
        var b = 0L
        var c = 0L
        var alice = 0L
        startWith {
            a = note("a", createdAt = now - 1)
            b = note("b", createdAt = now - 2)
            c = note("c", createdAt = now - 3)
            alice = person("Alice", createdAt = now)
        }
        awaitRow(a)
        longPress(a)
        compose.onNodeWithTag(rowTag(b)).performClick()
        ui.awaitText(selectedTitle(2))

        tapAction(EditorUi.ASSOCIATE)
        editor.button(EditorUi.ASSOCIATE).assertIsNotEnabled()
        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        editor.pickerTab(EditorUi.PROJECTS).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Alpha")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.pickerTab("People (1)").assertExists()
        editor.button(EditorUi.ASSOCIATE).performClick()

        ui.awaitText("2 notes associated")
        awaitNoSelection()
        assertEquals(2L, count("SELECT COUNT(*) FROM note_persons WHERE person_id = ?", alice))
        assertEquals(2L, count("SELECT COUNT(*) FROM note_projects y JOIN projects j ON j.id = y.project_id WHERE j.name = 'Alpha'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons WHERE note_id = ?", c))
    }

    @Test
    fun backInTheAssociatePicker_returnsNothing_andKeepsTheSelection() {
        var a = 0L
        startWith {
            a = note("a", createdAt = now)
            person("Alice", createdAt = now)
        }
        awaitRow(a)
        longPress(a)
        tapAction(EditorUi.ASSOCIATE)
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Zed")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()

        // The create tap keeps the focus in the field (ui.md § 7.6), so the keyboard is up and would take the Back.
        compose.closeKeyboard(compose.onNodeWithTag(EditorUi.PICKER_SEARCH), scene::onActivity)
        ui.pressBack()

        ui.awaitText(selectedTitle(1))
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).assertDoesNotExist()
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons"))
        assertEquals(1L, count("SELECT COUNT(*) FROM persons"))
    }

    /** DEC-57 fix: like the Set date and Delete dialogs, an Associate picker that lost its selection closes. */
    @Test
    fun theAssociatePicker_restoredWithoutItsSelection_closes_andLinksNothing() {
        var first = 0L
        startWith {
            person("Alice", createdAt = now)
            first = note("first", createdAt = now)
            repeat(UNSAVED_SELECTION - 1) { note("n$it", createdAt = now - 1 - it) }
        }
        awaitRow(first)
        longPress(first)
        tapAction(SELECT_ALL)
        ui.awaitText(selectedTitle(UNSAVED_SELECTION))
        tapAction(EditorUi.ASSOCIATE)
        ui.awaitTag(EditorUi.PICKER_SEARCH)

        scene.recreateAfterProcessDeath() // the selection was too large to be saved

        awaitRow(first)
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).assertDoesNotExist()
        awaitNoSelection()
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons"))
    }

    @Test
    fun theAssociatePicker_restoredWithItsSavedSelection_staysOpen() {
        var a = 0L
        startWith {
            a = note("a", createdAt = now)
            person("Alice", createdAt = now)
        }
        awaitRow(a)
        longPress(a)
        tapAction(EditorUi.ASSOCIATE)
        ui.awaitTag(EditorUi.PICKER_SEARCH)

        scene.recreateAfterProcessDeath()

        ui.awaitTag(EditorUi.PICKER_SEARCH)
        ui.pressBack()
        ui.awaitText(selectedTitle(1))
    }

    private companion object {
        /** Enough frames for the tab change and the returning tab's first effects. */
        const val RETURN_FRAMES = 10

        /** One id more than a saved selection holds (DEC-57). */
        const val UNSAVED_SELECTION = 1_001
    }
}
