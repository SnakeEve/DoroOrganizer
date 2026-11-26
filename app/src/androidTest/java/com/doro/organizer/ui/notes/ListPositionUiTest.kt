package com.doro.organizer.ui.notes

import android.content.pm.ActivityInfo
import android.text.format.DateFormat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListPositionComposeTest
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.pickerRowTag
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-58 (DEC-110, Q17; ui.md § 3.11, § 3.9 item 4, § 2.1): a list at its very top stays there when rows
 * arrive above its first row, the list an editor returns to reveals the note it created (a not-done note as the top
 * row, a done one as the last row), and a scrolled list keeps its position, also for edits and across a return. Over
 * 30 planted one-line notes, each at least a minute old, on `FakeAppTime`. (n2), at font scale 2, is
 * `ListPositionFontScaleTest`. (u3) adds to I-58 a drag that started before the take. I-64 (p1)–(p9), (w) and (t): a
 * newly chosen person, day or range starts its list at the top (rule 4, DEC-110 (4)); (p7b) adds to I-64 an empty day
 * tapped under a dragged grid, the empty item's case of (p7).
 */
@RunWith(AndroidJUnit4::class)
class ListPositionUiTest : ListPositionComposeTest() {
    private var alice = 0L
    private var bob = 0L
    private lateinit var aliceNotes: List<Long>
    private lateinit var bobNotes: List<Long>

    // The reveal of a note just created (rule 2).

    @Test
    fun a_atTheVeryTop_aNewNoteLeftWithBack_isTheTopRow_evenWithItsSaveHeldUntilTheListShows() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        startNewNote()
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(ids[0])
        hold.release()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun b_aNewNoteSaved_afterTheListStoppedCollecting_isTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        startNewNote()
        awaitListStopped()

        tapSave()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun b2_aNewNoteLeftWithBack_afterTheListStoppedCollecting_isTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        startNewNote()
        awaitListStopped()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun c_fromAScrolledList_aNewNoteIsTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        startNewNote()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun d_aNewNoteSavedAsDone_showsAsTheLastRow_aboveTheCreateButtonsClearance() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        startNewNote()
        compose.onNodeWithTag(DONE).performClick()

        tapBack()
        val noteId = awaitReveal()

        assertTrue("the new note's row is on screen", compose.onAllNodesWithTag(rowTag(noteId)).fetchSemanticsNodes().isNotEmpty())
        val row = ui.boundsOf(rowTag(noteId))
        val list = ui.boundsOf(NOTE_LIST)
        val clearance = with(compose.density) { FAB_CLEARANCE.toPx() }
        assertTrue("$row above the clearance of $list", row.bottom <= list.bottom - clearance + 1)
        assertClose(ui.boundsOf(rowTag(ids.last())).bottom, row.top)
    }

    @Test
    fun e_twoNotesOneAfterTheOther_fromAScrolledList_showTheSecondOnTop_andTheFirstRightBelow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        startNewNote("first")
        tapBack()
        val first = awaitReveal()
        time.advanceBy(Duration.ofMinutes(1))
        startNewNote("second")

        tapBack()
        compose.waitUntil(LONG_WAIT_MS) { marker.takes.count { it.noteId != null } == 2 }
        val second = awaitReveal()

        assertEquals(second, topRow().id)
        assertClose(ui.boundsOf(rowTag(second)).bottom, ui.boundsOf(rowTag(first)).top)
    }

    @Test
    fun f_peopleWithAPersonChosen_scrolled_aNewNotePrefilledWithThePerson_isTheTopRow() {
        lateinit var ids: List<Long>
        var alice = 0L
        startWith(settings = { setRemembered(EntityKind.Person, alice) }) {
            alice = person("Alice", createdAt = now - MINUTE)
            ids = notes { linkPerson(it, alice) }
        }
        openTab(Perspective.Individual)
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        startNewNote()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun g_aNoteUnlinkedFromTheShownProject_isMarkedOnlyAfterItsLastSave_andNothingMoves() {
        lateinit var ids: List<Long>
        var alpha = 0L
        startWith(settings = { setRemembered(EntityKind.Project, alpha) }) {
            alpha = project("Alpha", createdAt = now - MINUTE)
            ids = notes { linkProject(it, alpha) }
        }
        openTab(Perspective.Project)
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()
        startNewNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick() // covering the editor saves it: the first insert
        ui.awaitTag(EditorUi.PICKER_SEARCH)
        ui.pressBack()
        ui.awaitTag(EditorUi.CONTENT)
        compose.onNodeWithContentDescription("Remove Alpha").performClick()
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(before.id)
        compose.waitUntil(LONG_WAIT_MS) { default.idle }
        compose.waitForIdle()
        assertEquals("no mark while the unlinking save is held", emptyList<Any>(), marker.marks)
        hold.release()

        awaitReveal()
        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun h1_aPersonFilterHidingTheNewNote_movesNothing_andClearingItLaterDoesNotJumpToTheNote() {
        lateinit var ids: List<Long>
        var alice = 0L
        startWith {
            alice = person("Alice", createdAt = now - MINUTE)
            ids = notes { linkPerson(it, alice) }
        }
        awaitRow(ids[0])
        setPersonFilter(alice)
        scrollTo(SCROLLED)
        val before = topRow()
        startNewNote()

        tapBack()
        awaitReveal()

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
        val shown = emissions()
        setPersonFilter(null)
        awaitRowsAfter(shown)
        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun h2_anOpenSearchHidingTheNewNote_movesNothing() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        search("note")
        scrollTo(SCROLLED)
        val before = topRow()
        startNewNote()

        tapBack()
        awaitReveal()

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun i_aNoteDeletedInTheEditor_keepsNoMark_movesNothing_andShowsOneNoteDeleted() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()
        startNewNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        ui.awaitTag(EditorUi.PICKER_SEARCH)
        ui.pressBack()
        ui.awaitTag(EditorUi.CONTENT)

        assertNoMarkKeptAfter {
            editor.menu(EditorUi.DELETE_NOTE)
            compose.onNodeWithText(DELETE).performClick()
            ui.awaitText(ONE_NOTE_DELETED)
        }

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    // A list at its very top (rule 1).

    @Test
    fun j1_atTheVeryTop_aVisibleNoteEditedAndLeftWithBack_isTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[3])
        editNote(ids[3])
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(ids[0])
        val shown = emissions()
        hold.release()
        awaitRowsAfter(shown)

        assertEquals(ids[3], topRow().id)
    }

    @Test
    fun j2_atTheVeryTop_aDoneRowUncheckedWithItsCheckbox_isTheTopRow() {
        lateinit var ids: List<Long>
        startWith {
            ids = List(PLANTED) { i ->
                val created = now - (i + 1) * MINUTE
                note("note $i", createdAt = created, checkedAt = if (i < 3) null else created)
            }
        }
        awaitRow(ids[0])
        val done = ids.last() // the done row checked first: the fourth row
        awaitRow(done)
        assertNotEquals("a done row below the first row", done, topRow().id)
        val shown = emissions()

        compose.onNodeWithTag(checkboxTag(done), useUnmergedTree = true).performClick()
        awaitRowsAfter(shown)

        assertEquals(done, topRow().id)
    }

    // A scrolled list (rule 3).

    @Test
    fun k1_scrolled_theFirstVisibleNoteEdited_leavesTheNoteAboveItOnTop_andTheSecondRowInPlace() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val first = topRow()
        assertEquals(ids[SCROLLED], first.id)
        val second = ui.boundsOf(rowTag(ids[SCROLLED + 1])).top
        editNote(ids[SCROLLED])
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(ids[SCROLLED])
        val shown = emissions()
        hold.release()
        awaitRowsAfter(shown)

        val top = topRow()
        assertEquals(ids[SCROLLED - 1], top.id)
        assertClose(first.top, top.top)
        assertClose(second, ui.boundsOf(rowTag(ids[SCROLLED + 1])).top)
    }

    @Test
    fun k2_scrolled_theThirdVisibleNoteSavedWhileTheListStillCollects_leavesTheTopRowInPlace() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()
        editNote(ids[SCROLLED + 2])
        val hold = probe.holdNextSave(afterWrite = true)
        val shown = emissions()

        tapSave()
        awaitHeld(hold)
        compose.waitUntil(LONG_WAIT_MS) { emissions() > shown && default.idle }
        hold.release()
        ui.awaitTag(NOTE_LIST)
        compose.waitForIdle()

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun k3_scrolled_theFirstVisibleNoteTickedDoneAfterTheListStopped_leavesTheSecondRowOnTopInItsPlace() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val first = topRow()
        compose.onNodeWithTag(rowTag(ids[SCROLLED])).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        awaitListStopped()
        compose.onNodeWithTag(DONE).performClick()
        compose.waitUntil(LONG_WAIT_MS) { count("SELECT checked FROM notes WHERE id = ?", ids[SCROLLED]) == 1L }
        val left = System.nanoTime()

        tapBack()
        awaitRowsStartedAfter(left)

        val top = topRow()
        assertEquals(ids[SCROLLED + 1], top.id)
        assertClose(first.top, top.top)
    }

    @Test
    fun k4_scrolled_anEditedNoteThatIsNotTheFirstVisibleRow_leavesTheTopRowInPlace() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()
        editNote(ids[SCROLLED + 2])
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(before.id)
        val shown = emissions()
        hold.release()
        awaitRowsAfter(shown)

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun l_checkingTheFirstVisibleRow_keepsTheViewport_andATabTapBringsTheFirstNoteToTheTop() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val first = topRow()
        val shown = emissions()

        compose.onNodeWithTag(checkboxTag(ids[SCROLLED]), useUnmergedTree = true).performClick()
        awaitRowsAfter(shown)

        val top = topRow()
        assertEquals(ids[SCROLLED + 1], top.id)
        assertClose(first.top, top.top)
        openTab(Perspective.All)
        awaitTopRow(ids[0])
    }

    @Test
    fun m_aRotation_aTabSwitchAndAPickerReturn_keepAScrolledListsTopRowInPlace() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()

        scene.recreateActivity()
        ui.awaitTag(NOTE_LIST)
        compose.waitForIdle()
        assertTrue("rotation: ${topRow()} at $before", topRow().samePlaceAs(before))

        openTab(Perspective.Orphan)
        openTab(Perspective.All)
        ui.awaitTag(NOTE_LIST)
        assertTrue("tab switch: ${topRow()} at $before", topRow().samePlaceAs(before))

        compose.onNodeWithTag(PERSON_CHIP).performClick()
        ui.awaitTag(EditorUi.PICKER_SEARCH)
        ui.pressBack()
        ui.awaitTag(NOTE_LIST)
        compose.waitForIdle()
        assertTrue("picker: ${topRow()} at $before", topRow().samePlaceAs(before))
    }

    // Calendar, Orphans and an empty list.

    @Test
    fun n_calendarAtItsVeryTop_whoseFirstRowFits_showsANewNoteOfTheDayAsItsFirstRow_withTheGridInPlace() {
        lateinit var ids: List<Long>
        startWith { ids = notes(date = today) }
        openTab(Perspective.Date)
        awaitRow(ids[0])
        compose.waitForIdle()
        val grid = ui.boundsOf(MONTH_GRID).top
        val firstRow = ui.boundsOf(rowTag(ids[0]))
        val limit = wholeRowLimit()
        // The layout's own test, in whole px without tolerance. Where the first row's place ends below the clearance,
        // the reveal takes the note to the top instead: (n2).
        assumeTrue("the first row's place $firstRow ends below $limit on this device", firstRow.bottom <= limit)
        startNewNote()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
        assertClose(grid, ui.boundsOf(MONTH_GRID).top)
    }

    @Test
    fun o_orphansScrolled_aNewNoteWithoutLinks_isTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        openTab(Perspective.Orphan)
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        startNewNote()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun p_anEmptyList_showsItsFirstNote() {
        startWith()
        ui.awaitText(NO_DATA)
        startNewNote()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun q_calendarScrolledPastItsGrid_showsANewNoteOfTheDayAsTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes(date = today) }
        openTab(Perspective.Date)
        awaitRow(ids[0])
        scrollTo(CALENDAR_HEADER_ITEMS + SCROLLED)
        startNewNote()

        tapBack()

        assertEquals(awaitReveal(), topRow().id)
    }

    @Test
    fun r_atTheVeryTop_closingTheSearch_showsTheNewestNoteAsTheTopRow() {
        lateinit var ids: List<Long>
        startWith {
            ids = List(PLANTED) { i ->
                note(if (i < 5) "other $i" else "note $i", createdAt = now - (i + 1) * MINUTE)
            }
        }
        awaitRow(ids[0])
        search("note")
        awaitTopRow(ids[5])
        val shown = emissions()

        compose.onNodeWithContentDescription(CLOSE_SEARCH).performClick()
        awaitRowsAfter(shown)

        assertEquals(ids[0], topRow().id)
    }

    @Test
    fun s_atTheVeryTop_setDateOnASelectedUndatedLowerRow_showsItAsTheTopRow() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        val lower = ids[4]
        longPress(lower)
        tapAction(MORE_OPTIONS)
        compose.onNodeWithText(SET_DATE).performClick()
        ui.awaitText(SET_DATE_TITLE)
        val todaysNumber = time.today().dayOfMonth.toString()
        compose.onAllNodes(hasText(todaysNumber, substring = true) and hasClickAction()).onFirst().performClick()
        val shown = emissions()

        compose.onNodeWithText(OK).performClick()
        ui.awaitText(DATE_SET_ON_ONE)
        awaitRowsAfter(shown)

        assertEquals(lower, topRow().id)
    }

    // Drags and other navigation before the reveal.

    @Test
    fun u1_aDragInProgressWhenTheRereadArrives_keepsTheListFromTheNote() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        startNewNote()
        val hold = holdReread()

        tapBack()
        awaitHeldReread(hold)
        compose.onNodeWithTag(NOTE_LIST).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 4))
        }
        hold.release()
        val noteId = awaitReveal()
        compose.onNodeWithTag(NOTE_LIST).performTouchInput { up() }
        compose.waitForIdle()

        assertNotEquals(noteId, topRow().id)
        assertNoRowOnScreen(noteId)
    }

    @Test
    fun u2_aDragThatStartedAndEndedAfterTheTake_keepsTheListFromTheNote() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        startNewNote()
        val hold = holdReread()

        tapBack()
        awaitHeldReread(hold)
        compose.onNodeWithTag(NOTE_LIST).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 4), delayMillis = 200)
            moveBy(Offset(0f, -1f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        hold.release()
        val noteId = awaitReveal()

        assertNotEquals(noteId, topRow().id)
        assertNoRowOnScreen(noteId)
    }

    @Test
    fun u3_aDragInProgressBeforeTheTake_keepsTheListFromTheNote() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()
        startNewNote()
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(before.id)
        // The mark, and with it the take, waits for the held save: the finger is down before the wait starts, so only
        // the drag in progress when the rows arrive keeps the list from the note.
        assertEquals("marks before the drag", emptyList<Any>(), marker.marks)
        compose.onNodeWithTag(NOTE_LIST).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 4))
        }
        hold.release()
        val noteId = awaitReveal()
        compose.onNodeWithTag(NOTE_LIST).performTouchInput { up() }
        compose.waitForIdle()

        assertNoRowOnScreen(noteId)
    }

    @Test
    fun v1_aTabSwitchWhileTheInsertAfterBackIsHeld_keepsNoMark_andMovesNeitherList() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        openTab(Perspective.Orphan)
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val orphans = topRow()
        openTab(Perspective.All)
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val all = topRow()
        startNewNote()
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        val shown = emissions()
        openTab(Perspective.Orphan)
        hold.release()
        assertMarkIgnored()
        awaitRowsAfter(shown)

        assertTrue("Orphans: ${topRow()} at $orphans", topRow().samePlaceAs(orphans))
        openTab(Perspective.All)
        compose.waitForIdle()
        assertTrue("All: ${topRow()} at $all", topRow().samePlaceAs(all))
    }

    @Test
    fun v2_aRowOpenedWhileTheInsertAfterBackIsHeld_keepsNoMark_andMovesNothing() {
        lateinit var ids: List<Long>
        startWith { ids = notes() }
        awaitRow(ids[0])
        scrollTo(SCROLLED)
        val before = topRow()
        startNewNote()
        val hold = probe.holdNextSave(afterWrite = false)

        tapBack()
        awaitHeld(hold)
        awaitTopRow(before.id)
        compose.onNodeWithTag(rowTag(before.id)).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        hold.release()
        assertMarkIgnored()
        tapBack()
        ui.awaitTag(NOTE_LIST)
        compose.waitUntil(LONG_WAIT_MS) { default.idle }
        compose.waitForIdle()

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    // A newly chosen primary (rule 4, DEC-110 (4)): People on Alice's or Bob's notes, the Calendar on two days.

    @Test
    fun p1_aScrolledPersonsList_anotherPersonChosenInTheTitle_startsAtTheirFirstNote() {
        peopleScrolled()
        openTitlePicker("Alice")
        val chosen = System.nanoTime()

        ui.click(pickerRowTag(bob))
        awaitRowsOf(chosen) { it.personId == bob }

        assertEquals(bobNotes[0], topRow().id)
    }

    @Test
    fun p2_afterAllPeople_theSamePersonChosenOnTheBody_startsAtTheirFirstNote() {
        peopleScrolled()
        openTitlePicker("Alice")
        compose.onNodeWithTag(PICKER_ALL).performClick()
        awaitPickerBody()
        val chosen = System.nanoTime()

        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        awaitRowsOf(chosen) { it.personId == alice }

        assertEquals(aliceNotes[0], topRow().id)
    }

    @Test
    fun p3_afterTheShownPersonWasDeleted_anotherChosenOnTheBody_startsAtTheirFirstNote() {
        peopleScrolled()
        runBlocking { scene.app.container.entityRepository.deletePerson(alice).ok() }
        awaitPickerBody()
        val chosen = System.nanoTime()

        compose.onNodeWithTag(pickerRowTag(bob)).performClick()
        awaitRowsOf(chosen) { it.personId == bob }

        assertEquals(bobNotes[0], topRow().id)
    }

    @Test
    fun p4_theSamePersonChosenAgainInTheTitle_keepsTheTopRowInPlace() {
        peopleScrolled()
        val before = topRow()
        openTitlePicker("Alice")

        ui.click(pickerRowTag(alice))
        ui.awaitTag(NOTE_LIST)
        compose.waitUntil(LONG_WAIT_MS) { default.idle }
        compose.waitForIdle()

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun p5_aRotation_keepsAScrolledPersonsTopRowInPlace() {
        peopleScrolled()
        val before = topRow()

        scene.recreateActivity()
        ui.awaitTag(NOTE_LIST)
        compose.waitUntil(LONG_WAIT_MS) { default.idle }
        compose.waitForIdle()

        assertTrue("${topRow()} at $before", topRow().samePlaceAs(before))
    }

    @Test
    fun p6_anotherPersonAfterAProcessDeathWithTheTitlesPickerOpen_startsAtTheirFirstNote() {
        peopleScrolled()
        openTitlePicker("Alice")
        scene.recreateAfterProcessDeath()
        ui.awaitText(CHOOSE_PERSON)
        // Bob is then the restored list's first decided primary: only the saved record tells him from a first decision.
        runBlocking { scene.settings.setRemembered(EntityKind.Person, bob) }
        val back = System.nanoTime()

        ui.pressBack()
        awaitRowsOf(back) { it.personId == bob }

        assertEquals(bobNotes[0], topRow().id)
    }

    @Test
    fun p7_singleColumnCalendar_withItsGridOnScreen_anotherDayTapped_keepsTheGridInPlace() {
        val days = plantTwoDays()
        openTab(Perspective.Date)
        awaitRow(days.first[0])
        dragGridUpByAThird()
        val grid = layoutTopOf(MONTH_GRID)

        tapDayAndAwaitItsRows(otherDay)

        assertClose(grid, layoutTopOf(MONTH_GRID))
    }

    @Test
    fun p7b_singleColumnCalendar_withItsGridOnScreen_anEmptyDayTapped_keepsTheGridInPlace() {
        val days = plantTwoDays()
        openTab(Perspective.Date)
        awaitRow(days.first[0])
        dragGridUpByAThird()
        val grid = layoutTopOf(MONTH_GRID)

        tapDayAndAwaitItsRows(emptyDay)

        ui.awaitText(NO_DATA)
        assertClose(grid, layoutTopOf(MONTH_GRID))
    }

    @Test
    fun p8_twoPaneCalendar_scrolled_anotherDayWhoseRowsArriveBeforeAFrame_startsAtItsFirstNote() {
        val days = plantTwoDays()
        openTab(Perspective.Date)
        awaitRow(days.first[0])
        assumeTrue("the two-pane Calendar in landscape", turnToTwoPanes())
        scrollTo(PRIMARY_SCROLLED)
        assertEquals(days.first[PRIMARY_SCROLLED], topRow().id)

        withPausedClock { clickAndAwaitRowsWithoutAFrame(otherDay) }
        compose.waitForIdle()

        assertEquals(days.second[0], topRow().id)
    }

    @Test
    fun p9_twoPaneCalendar_anotherDayTappedDuringAFling_startsAtItsFirstNote_atTheListsTop() {
        val days = plantTwoDays(firstDayNotes = FLING_NOTES)
        openTab(Perspective.Date)
        awaitRow(days.first[0])
        assumeTrue("the two-pane Calendar in landscape", turnToTwoPanes())

        withPausedClock {
            compose.onNodeWithTag(NOTE_LIST).performTouchInput { swipeUp(durationMillis = FLING_SWIPE_MS) }
            compose.mainClock.advanceTimeByFrame()
            val first = scrollMark()
            compose.mainClock.advanceTimeByFrame()
            val second = scrollMark()
            assertNotEquals("the fling runs", first, second)
            clickAndAwaitRowsWithoutAFrame(otherDay)
        }
        compose.waitForIdle()

        val top = topRow()
        assertEquals(days.second[0], top.id)
        assertClose(ui.boundsOf(NOTE_LIST).top, layoutTopOf(rowTag(top.id)))
    }

    @Test
    fun w_singleColumnCalendar_aRangeChosenWhileANewNotesRevealWaits_keepsTheSelectionHeaderInPlace() {
        val days = plantTwoDays()
        openTab(Perspective.Date)
        awaitRow(days.first[0])
        dragGridUpByAThird()
        val header = selectionHeaderTop()
        startNewNote()
        val hold = holdReread()
        tapBack()
        awaitHeldReread(hold)

        // The range dialog keeps the screen composed, so only rule 4 ends the wait: the range's rows hold the note.
        compose.onNodeWithText(SELECT_RANGE).performClick()
        compose.onNode(dialogDay(otherDay)).performClick()
        compose.waitForIdle() // OK reads the range of the composition after the tap
        val chosen = System.nanoTime()
        compose.onNodeWithText(OK).performClick()
        awaitRowsOf(chosen) { it.fromDay == today && it.toDay == otherDay }
        hold.release()
        compose.waitForIdle()

        assertTrue("the grid scrolled off", compose.onAllNodesWithTag(MONTH_GRID).fetchSemanticsNodes().isNotEmpty())
        assertClose(header, selectionHeaderTop())
    }

    @Test
    fun t_aRevealDroppedWhenTheTitleWasTapped_isNotStartedByTheNextPerson() {
        peopleScrolled()
        startNewNote()
        compose.onNodeWithTag(DONE).performClick()
        val hold = holdReread()
        tapBack()
        awaitHeldReread(hold)
        val noteId = checkNotNull(marker.takes.last { it.noteId != null }.noteId)

        openTitlePicker("Alice")
        hold.release()
        compose.onNodeWithTag(PICKER_ALL).performClick()
        awaitPickerBody()
        val chosen = System.nanoTime()
        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        awaitRowsOf(chosen) { it.personId == alice }

        assertEquals(aliceNotes[0], topRow().id)
        assertNoRowOnScreen(noteId)
    }

    /** Opens the editor of note [id] and appends a word to its first line. */
    private fun editNote(id: Long) {
        compose.onNodeWithTag(rowTag(id)).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        compose.waitUntil(LONG_WAIT_MS) { editor.contentText().isNotEmpty() }
        compose.onNodeWithTag(EditorUi.CONTENT).performTextInput(" edited")
    }

    /** Opens the search and types [text]; returns once the filtered rows are on screen. */
    private fun search(text: String) {
        compose.onNodeWithContentDescription(SEARCH).performClick()
        val typed = System.nanoTime()
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput(text)
        awaitRowsStartedAfter(typed)
    }

    /** The Person chip's picker: [id]'s row, or "Any person" for null. */
    private fun setPersonFilter(id: Long?) {
        compose.onNodeWithTag(PERSON_CHIP).performClick()
        ui.awaitTag(EditorUi.PICKER_SEARCH)
        if (id == null) compose.onNodeWithText(ANY_PERSON).performClick() else ui.click(pickerRowTag(id))
        ui.awaitTag(NOTE_LIST)
        compose.waitUntil(LONG_WAIT_MS) { default.idle }
        compose.waitForIdle()
    }

    /**
     * I-64's People: Alice and Bob linked to [PLANTED] one-line notes each, Alice remembered and her list scrolled to
     * row [PRIMARY_SCROLLED].
     */
    private fun peopleScrolled() {
        startWith(settings = { setRemembered(EntityKind.Person, alice) }) {
            alice = person("Alice", createdAt = now - MINUTE)
            bob = person("Bob", createdAt = now - MINUTE)
            aliceNotes = notes { linkPerson(it, alice) }
            bobNotes = List(PLANTED) { i ->
                note("bob $i", createdAt = now - (i + 1) * MINUTE).also { linkPerson(it, bob) }
            }
        }
        openTab(Perspective.Individual)
        awaitRow(aliceNotes[0])
        scrollTo(PRIMARY_SCROLLED)
        assertEquals(aliceNotes[PRIMARY_SCROLLED], topRow().id)
    }

    /** [firstDayNotes] notes of today and [PLANTED] of [otherDay], in the displayed month; their ids per day. */
    private fun plantTwoDays(firstDayNotes: Int = PLANTED): Pair<List<Long>, List<Long>> {
        lateinit var first: List<Long>
        lateinit var second: List<Long>
        startWith {
            first = notes(count = firstDayNotes, date = today)
            second = List(PLANTED) { i -> note("other $i", createdAt = now - (i + 1) * MINUTE, date = otherDay) }
        }
        return first to second
    }

    /** The day after today, in the same month (2026-11-17 on `FakeAppTime`). */
    private val otherDay: Long get() = today + 1

    /** A day of the same month with no notes. */
    private val emptyDay: Long get() = today + 2

    /**
     * Waits until a rows collection whose query matches [query], started after [nanos], has emitted and its rows
     * reached the screen.
     */
    private fun awaitRowsOf(nanos: Long, query: (NoteQuery) -> Boolean) {
        compose.waitUntil(LONG_WAIT_MS) { rowsRead(nanos, query) }
        compose.waitForIdle()
    }

    /** A rows collection of [query] started after [nanos] has emitted, and no rows are being mapped. */
    private fun rowsRead(nanos: Long, query: (NoteQuery) -> Boolean): Boolean =
        probe.collections.any { it.startedAt > nanos && it.emissions > 0 && query(it.query) } && default.idle

    /** Taps [day] in the grid, frames running, and waits until its rows are read and on screen. */
    private fun tapDayAndAwaitItsRows(day: Long) {
        val tapped = System.nanoTime()
        compose.onNodeWithTag(dayTag(day)).performClick()
        awaitRowsOf(tapped) { it.fromDay == day && it.toDay == day }
    }

    /**
     * The day [epochDay] in the open date picker dialog, which reads each day's full date (Material's `yMMMMEEEEd`
     * skeleton in the default locale).
     */
    private fun dialogDay(epochDay: Long): SemanticsMatcher {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMMEEEEd")
        val date = DateTimeFormatter.ofPattern(pattern, locale).format(LocalDate.ofEpochDay(epochDay))
        return hasText(date, substring = true) and hasClickAction() and hasAnyAncestor(isDialog())
    }

    /**
     * Clicks [day] through its semantics action, which injects no input, and waits until its rows are read and mapped,
     * with no frame in between: the main clock is paused, and the wait reads no semantics.
     */
    private fun clickAndAwaitRowsWithoutAFrame(day: Long) {
        val clicked = System.nanoTime()
        compose.onNodeWithTag(dayTag(day)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(LONG_WAIT_MS) { rowsRead(clicked) { it.fromDay == day && it.toDay == day } }
    }

    /** Runs [block] with the main clock paused, so no frame is drawn unless [block] advances it. */
    private fun withPausedClock(block: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            block()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Drags the single-column Calendar up by about a third of its grid's height, slowly enough for no fling. */
    private fun dragGridUpByAThird() {
        val third = compose.onNodeWithTag(MONTH_GRID).fetchSemanticsNode().size.height / 3f
        compose.onNodeWithTag(NOTE_LIST).performTouchInput {
            down(center)
            moveBy(Offset(0f, -third), delayMillis = 200)
            moveBy(Offset(0f, -1f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        val grid = layoutTopOf(MONTH_GRID)
        val list = ui.boundsOf(NOTE_LIST)
        assertTrue("the grid at $grid moved up but is on screen in $list", grid < list.top && ui.boundsOf(MONTH_GRID).bottom > list.top)
    }

    /** The top of the node of [tag] as laid out, in px in the root: unlike its bounds, not clipped by the list. */
    private fun layoutTopOf(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    /**
     * The top of the single-column Calendar's selection header as laid out: the bottom of the grid, the item right
     * above it.
     */
    private fun selectionHeaderTop(): Float =
        compose.onNodeWithTag(MONTH_GRID).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }

    /** The list's first displayed row and where it is laid out: it changes with every scrolled pixel. */
    private fun scrollMark(): Pair<Long, Float> = topRow().id.let { it to layoutTopOf(rowTag(it)) }

    /**
     * Turns the activity to landscape and tells whether the Calendar then shows two panes (ui.md § 4.4): the grid beside
     * the day list.
     */
    private fun turnToTwoPanes(): Boolean {
        scene.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(LONG_WAIT_MS) {
            val root = compose.onRoot().fetchSemanticsNode().size
            root.width > root.height
        }
        ui.awaitTag(MONTH_GRID)
        ui.awaitTag(NOTE_LIST)
        compose.waitForIdle()
        return ui.boundsOf(MONTH_GRID).right <= ui.boundsOf(NOTE_LIST).left + 1
    }

    private fun assertNoRowOnScreen(id: Long) {
        val list = ui.boundsOf(NOTE_LIST)
        val shown = runCatching { ui.boundsOf(rowTag(id)) }.getOrNull()
        assertTrue("row $id is on screen at $shown", shown == null || shown.bottom <= list.top || shown.top >= list.bottom)
    }

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue("$actual is not within 1 px of $expected", abs(expected - actual) <= 1f)

    private companion object {
        /** The first visible row of a scrolled list: no header item, nothing of the top rows on screen. */
        const val SCROLLED = 10

        /** I-64's scrolled list of a primary. */
        const val PRIMARY_SCROLLED = 12

        /** The notes of the day a fling runs over (I-64 (p9)), and the swipe that starts it. */
        const val FLING_NOTES = 200
        const val FLING_SWIPE_MS = 100L
        const val DONE = "editor_done"
        const val PERSON_CHIP = "chip_Person"
        const val SEARCH_FIELD = "search_field"

        // Literals copied from ui.md § 11.
        const val ONE_NOTE_DELETED = "1 note deleted"
        const val ANY_PERSON = "Any person"
        const val CLOSE_SEARCH = "Close search"
        const val SET_DATE_TITLE = "Set date"
        const val OK = "OK"
        const val DATE_SET_ON_ONE = "Date set on 1 note"
        const val SELECT_RANGE = "Select range"
    }
}
