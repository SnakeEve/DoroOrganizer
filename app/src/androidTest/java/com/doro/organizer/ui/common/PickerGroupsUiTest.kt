package com.doro.organizer.ui.common

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.RowsProbe
import com.doro.organizer.testing.pickerRowTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-60 and I-62 (a), (b) (DEC-111, Q19, Q20, Q30, Q31; ui.md § 6.4, § 7.4, § 7.6): the groups of the
 * pickers that link people over the real app and database. Planted: people Anna, Bob, Cid (no notes), Yan and Zed;
 * projects Apollo, Gemini and Zeus (no notes); notes n1 {Apollo, Yan}, n2 {Apollo, Zed}, n3 {Gemini, Bob}. The first
 * group's people sort after the others by name, so the order without groups differs. Positions compare the tops of the
 * row and header nodes; a check that nothing moved first waits until the changed count is on screen. The editor's
 * saves go through [RowsProbe], which holds the next one where a case needs it.
 */
@RunWith(AndroidJUnit4::class)
class PickerGroupsUiTest : ListComposeTest() {
    private lateinit var probe: RowsProbe

    private var anna = 0L
    private var bob = 0L
    private var cid = 0L
    private var yan = 0L
    private var zed = 0L
    private var apollo = 0L
    private var gemini = 0L
    private var zeus = 0L
    private var n1 = 0L
    private var n3 = 0L
    private var n4 = 0L

    init {
        scene.wrapNoteRepository = { real -> RowsProbe(real).also { probe = it } }
    }

    // I-60 (a)–(k).

    @Test
    fun addPerson_onANoteOfOneProject_listsItsPeopleFirst_underAHeading_andATickMovesNobody() {
        openAddPersonOnApollo()

        compose.onNode(header(NOTE_PROJECT)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTopDown(header(NOTE_PROJECT), row(yan), row(zed), header(OTHER_PEOPLE), row(anna), row(bob), row(cid))
        compose.onNode(row(anna) and hasText("0 notes · added", substring = true)).assertExists()
        compose.onNode(row(cid) and hasText("0 notes · added", substring = true)).assertExists()
        val before = tops()

        compose.onNodeWithTag(pickerRowTag(anna)).performClick()

        compose.onNodeWithTag(pickerRowTag(anna)).assertIsOn()
        assertEquals("a tick moves no row", before, tops())
    }

    @Test
    fun addPerson_onANoteOfTwoProjects_listsThePeopleOfBoth_underTheirPluralHeader() {
        startPlanted()
        editor.createNote()
        compose.onNodeWithText(ADD_PROJECT).performClick()
        compose.onNodeWithTag(pickerRowTag(apollo)).performClick()
        compose.onNodeWithTag(pickerRowTag(gemini)).performClick()
        editor.button(DONE).performClick()
        ui.awaitDescription("Remove Gemini")

        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()

        ui.awaitTag(pickerRowTag(anna))
        assertTopDown(header(NOTE_PROJECTS), row(bob), row(yan), row(zed), header(OTHER_PEOPLE), row(anna), row(cid))
    }

    @Test
    fun theCreateRowAndThePeopleCreatedInThisVisit_stayAboveTheFirstHeader() {
        openAddPersonOnApollo()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Dora")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        ui.awaitText("New")

        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("a")

        ui.awaitText("Create \"a\"")
        assertTopDown(
            hasTestTag(EditorUi.PICKER_CREATE),
            createdRow,
            header(NOTE_PROJECT),
            row(yan),
            header(OTHER_PEOPLE),
            row(anna),
        )
    }

    @Test
    fun aPersonTickedInTheLastVisit_isInTheFirstGroup_beforeAndAfterTheSaveThatOpeningStarts() {
        openAddPersonOnApollo()
        compose.onNodeWithTag(pickerRowTag(anna)).performClick()
        editor.button(DONE).performClick()
        ui.awaitDescription("Remove Anna")
        val hold = probe.holdNextSave(afterWrite = false)

        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()

        awaitHeld(hold)
        ui.awaitTag(pickerRowTag(cid))
        val grouped = arrayOf(header(NOTE_PROJECT), row(anna), row(yan), row(zed), header(OTHER_PEOPLE), row(bob), row(cid))
        assertTopDown(*grouped)
        compose.onNodeWithTag(pickerRowTag(anna)).assertIsOn()
        compose.onNode(row(anna) and hasText("0 notes · added", substring = true)).assertExists()

        hold.release()

        awaitRowText(anna, "1 note · added")
        assertTopDown(*grouped)
    }

    @Test
    fun aChipRemovedBeforeAddPerson_putsThatPersonInTheOtherGroup_thoughTheStoredNoteStillLinksHim() {
        startPlanted(yanOnN1 = false, withN4 = true)
        compose.onNodeWithTag(rowTag(n4)).performClick()
        ui.awaitDescription("Remove Yan")
        compose.onNodeWithContentDescription("Remove Yan").performClick()
        val hold = probe.holdNextSave(afterWrite = false)

        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()

        awaitHeld(hold)
        ui.awaitTag(pickerRowTag(yan))
        val grouped = arrayOf(header(NOTE_PROJECT), row(zed), header(OTHER_PEOPLE), row(anna), row(bob), row(cid), row(yan))
        assertTopDown(*grouped)
        compose.onNode(row(yan) and hasText("1 note · added", substring = true)).assertExists()

        hold.release()

        awaitRowText(yan, "0 notes · added")
        assertTopDown(*grouped)
    }

    @Test
    fun aWriteWhileThePickerIsOpen_movesNobody() {
        openAddPersonOnApollo()
        val grouped = arrayOf(header(NOTE_PROJECT), row(yan), row(zed), header(OTHER_PEOPLE), row(anna), row(bob), row(cid))
        assertTopDown(*grouped)

        val result = runBlocking {
            scene.app.container.noteRepository.associate(listOf(n1), setOf(bob), emptySet(), emptyList(), emptyList())
        }

        assertTrue("$result", result is AppResult.Ok)
        awaitRowText(bob, "2 notes · added")
        assertTopDown(*grouped)
    }

    @Test
    fun addProject_keepsItsOrderByName_withoutAHeader() {
        startPlanted()
        compose.onNodeWithTag(rowTag(n3)).performClick() // Gemini and Bob
        ui.awaitDescription("Remove Bob")

        compose.onNodeWithText(ADD_PROJECT).performClick()

        ui.awaitTag(pickerRowTag(zeus))
        assertTopDown(row(apollo), row(gemini), row(zeus))
        compose.onNodeWithTag(pickerRowTag(gemini)).assertIsOn()
        assertNoGroupHeader()
    }

    @Test
    fun associateInProject_headsThePeopleOfTheProject_andNotItsProjectsTab() {
        startPlanted(rememberApollo = true)
        openTab(Perspective.Project)
        awaitRow(n1)
        longPress(n1)

        tapAction(EditorUi.ASSOCIATE)

        ui.awaitTag(pickerRowTag(cid))
        assertTopDown(header(CO_PEOPLE), row(yan), row(zed), header(OTHER_PEOPLE), row(anna), row(bob), row(cid))
        editor.pickerTab(EditorUi.PROJECTS).performClick()
        ui.awaitTag(pickerRowTag(zeus))
        assertTopDown(row(apollo), row(gemini), row(zeus))
        assertNoGroupHeader()
    }

    @Test
    fun associateInIndividual_hasNoHeaderOnEitherTab() {
        startPlanted(rememberBob = true)
        openTab(Perspective.Individual)
        awaitRow(n3)
        longPress(n3)

        tapAction(EditorUi.ASSOCIATE)

        ui.awaitTag(pickerRowTag(cid))
        assertTopDown(row(anna), row(bob), row(cid), row(yan), row(zed))
        assertNoGroupHeader()
        editor.pickerTab(EditorUi.PROJECTS).performClick()
        ui.awaitTag(pickerRowTag(zeus))
        assertTopDown(row(apollo), row(gemini), row(zeus))
        assertNoGroupHeader()
    }

    @Test
    fun thePersonFilterOfAProject_listsAnyThenTheProjectsPeopleThenTheOthers() {
        startPlanted(rememberApollo = true)
        openTab(Perspective.Project)
        awaitRow(n1)

        compose.onNodeWithTag("chip_Person").performClick()

        ui.awaitTag(pickerRowTag(cid))
        assertTopDown(
            hasText(ANY_PERSON),
            header(CO_PEOPLE),
            row(yan),
            row(zed),
            header(OTHER_PEOPLE),
            row(anna),
            row(bob),
            row(cid),
        )
    }

    @Test
    fun theHeaders_showOnlyWhileBothGroupsHaveRowsAfterTheSearch() {
        openAddPersonOnApollo()

        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("an")

        compose.waitUntil(GateUi.TIMEOUT_MS) { notShown(row(zed)) }
        assertTopDown(header(NOTE_PROJECT), row(yan), header(OTHER_PEOPLE), row(anna))
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextReplacement("ann")
        compose.waitUntil(GateUi.TIMEOUT_MS) { notShown(row(yan)) }
        compose.onNode(row(anna)).assertExists()
        assertNoGroupHeader()
    }

    // I-62 (a), (b): an entity created in an earlier visit of the same edit.

    @Test
    fun aPersonCreatedInAnEarlierVisit_isSavedFirst_thenListedCheckedInTheFirstGroup_andUntickedLeavesTheNote() {
        openAddPersonOnApollo()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Dora")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(DONE).performClick()
        ui.awaitDescription("Remove Dora")
        val hold = probe.holdNextSave(afterWrite = false)

        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()

        awaitHeld(hold)
        compose.waitForIdle()
        compose.onNodeWithTag(EditorUi.CONTENT).assertExists()
        assertTrue("no picker while the save is held", notShown(hasText(ADD_PEOPLE)))
        hold.release()
        ui.awaitText(ADD_PEOPLE)
        val dora = count("SELECT id FROM persons WHERE name = ?", "Dora")
        ui.awaitTag(pickerRowTag(dora))
        assertTopDown(header(NOTE_PROJECT), row(dora), row(yan), row(zed), header(OTHER_PEOPLE), row(anna))
        compose.onNodeWithTag(pickerRowTag(dora)).assertIsOn()
        compose.onNode(row(dora) and hasText("1 note · added", substring = true)).assertExists()

        compose.onNodeWithTag(pickerRowTag(dora)).performClick()
        editor.button(DONE).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        assertTrue(compose.onAllNodesWithContentDescription("Remove Dora").fetchSemanticsNodes().isEmpty())
        awaitDetachedWork { leaveEditor() }

        val note = count("SELECT MAX(id) FROM notes")
        assertEquals(1L, count("SELECT COUNT(*) FROM note_projects WHERE note_id = ? AND project_id = ?", note, apollo))
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons WHERE note_id = ?", note))
        assertEquals("Dora stays a person", 1L, count("SELECT COUNT(*) FROM persons WHERE id = ?", dora))
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons WHERE person_id = ?", dora))
    }

    @Test
    fun aProjectCreatedInAnEarlierVisit_isListedChecked_withoutAHeader_cancelKeepsIt_andUntickedLeaves() {
        startPlanted()
        editor.createNote()
        compose.onNodeWithText(ADD_PROJECT).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Nova")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(DONE).performClick()
        ui.awaitDescription(REMOVE_NOVA)

        compose.onNodeWithText(ADD_PROJECT).performClick()

        ui.awaitText(ADD_PROJECTS)
        val nova = count("SELECT id FROM projects WHERE name = ?", "Nova")
        ui.awaitTag(pickerRowTag(nova))
        compose.onNodeWithTag(pickerRowTag(nova)).assertIsOn()
        assertTopDown(row(apollo), row(gemini), row(nova), row(zeus))
        assertNoGroupHeader()
        compose.onNodeWithTag(pickerRowTag(nova)).performClick()
        compose.onNode(describedWith(EditorUi.CANCEL)).performClick()
        ui.awaitDescription(REMOVE_NOVA)

        compose.onNodeWithText(ADD_PROJECT).performClick()
        ui.awaitTag(pickerRowTag(nova))
        compose.onNodeWithTag(pickerRowTag(nova)).assertIsOn().performClick().assertIsOff()
        editor.button(DONE).performClick()

        ui.awaitTag(EditorUi.CONTENT)
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(REMOVE_NOVA).fetchSemanticsNodes().isEmpty()
        }
    }

    // Harness.

    /**
     * Plants the people, projects and notes of this class, remembers Apollo for Projects and Bob for People when
     * asked, and starts the app on All. Without [yanOnN1], n1 links Apollo alone; [withN4] adds n4 {Apollo, Yan, Zed}.
     */
    private fun startPlanted(
        rememberApollo: Boolean = false,
        rememberBob: Boolean = false,
        yanOnN1: Boolean = true,
        withN4: Boolean = false,
    ) {
        startWith(
            settings = {
                if (rememberApollo) setRemembered(EntityKind.Project, apollo)
                if (rememberBob) setRemembered(EntityKind.Person, bob)
            },
        ) {
            anna = person("Anna", now - 9 * MINUTE)
            bob = person("Bob", now - 8 * MINUTE)
            cid = person("Cid", now - 7 * MINUTE)
            yan = person("Yan", now - 6 * MINUTE)
            zed = person("Zed", now - 5 * MINUTE)
            apollo = project("Apollo", now - 9 * MINUTE)
            gemini = project("Gemini", now - 8 * MINUTE)
            zeus = project("Zeus", now - 7 * MINUTE)
            n1 = note("n1", createdAt = now - 4 * MINUTE).also { linkProject(it, apollo) }
            if (yanOnN1) linkPerson(n1, yan)
            note("n2", createdAt = now - 3 * MINUTE).also {
                linkProject(it, apollo)
                linkPerson(it, zed)
            }
            n3 = note("n3", createdAt = now - 2 * MINUTE).also {
                linkProject(it, gemini)
                linkPerson(it, bob)
            }
            if (withN4) {
                n4 = note("n4", createdAt = now - MINUTE).also {
                    linkProject(it, apollo)
                    linkPerson(it, yan)
                    linkPerson(it, zed)
                }
            }
        }
    }

    /** Projects with Apollo chosen, +, Add person, until the picker lists Cid. */
    private fun openAddPersonOnApollo() {
        startPlanted(rememberApollo = true)
        openTab(Perspective.Project)
        awaitRow(n1)
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        ui.awaitTag(pickerRowTag(cid))
    }

    private fun row(id: Long) = hasTestTag(pickerRowTag(id))

    private fun header(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private val createdRow = SemanticsMatcher("an entity created in this visit") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("picker_new_") == true
    }

    /** [nodes] are shown in this order from the top, each below the one before. */
    private fun assertTopDown(vararg nodes: SemanticsMatcher) {
        val tops = nodes.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("tops $tops of $nodes", tops.zipWithNext().all { (above, below) -> above < below })
    }

    /** The top of every picker row and header on screen, by row tag or header text. */
    private fun tops(): Map<String, Float> {
        val nodes = compose.onAllNodes(
            SemanticsMatcher("a picker row or header") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("picker_row_") == true ||
                    it.config.contains(SemanticsProperties.Heading)
            },
        ).fetchSemanticsNodes()
        return nodes.associate { node ->
            val name = node.config.getOrNull(SemanticsProperties.TestTag)
                ?: node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
            name to node.boundsInRoot.top
        }
    }

    private fun notShown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    private fun assertNoGroupHeader() {
        listOf(NOTE_PROJECT, NOTE_PROJECTS, CO_PEOPLE, OTHER_PEOPLE, OTHER_PROJECTS, CO_PROJECTS).forEach {
            assertTrue("header $it shown", compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty())
        }
    }

    /** Waits until the row of [id] shows [text], such as its new count. */
    private fun awaitRowText(id: Long, text: String) = compose.waitUntil(GateUi.TIMEOUT_MS) {
        !notShown(row(id) and hasText(text, substring = true))
    }

    /**
     * Waits until [hold] holds the editor's save. Compose runs effects only while the test synchronizes, and the save
     * starts from the editor's entry, so this pumps frames instead of blocking.
     */
    private fun awaitHeld(hold: RowsProbe.Hold) = compose.waitUntil(GateUi.TIMEOUT_MS) { hold.reached.isCompleted }

    /** Back from the editor, until its entry has left the composition and started its save on leave. */
    private fun leaveEditor() {
        ui.pressBack()
        ui.awaitTag("fab_create")
        compose.waitUntil(GateUi.TIMEOUT_MS) { notShown(hasTestTag(EditorUi.CONTENT)) }
    }

    private companion object {
        const val MINUTE = 60_000L

        // Literals copied from ui.md § 11.
        const val NOTE_PROJECT = "People on this note's project"
        const val NOTE_PROJECTS = "People on this note's projects"
        const val CO_PEOPLE = "People on this project's notes"
        const val CO_PROJECTS = "Projects on this person's notes"
        const val OTHER_PEOPLE = "Other people"
        const val OTHER_PROJECTS = "Other projects"
        const val ANY_PERSON = "Any person"
        const val ADD_PROJECT = "Add project"
        const val ADD_PEOPLE = "Add people"
        const val ADD_PROJECTS = "Add projects"
        const val DONE = "Done"
        const val REMOVE_NOVA = "Remove Nova"
    }
}
