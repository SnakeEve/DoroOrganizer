package com.doro.organizer.ui.notes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListPositionComposeTest
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.awaitDialogIme
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.pickerRowTag
import com.doro.organizer.testing.saved
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-66 (DEC-113, Q21–Q23; ui.md § 4.2, § 8.4, § 8.17): the overflow menu of People and Projects renames or
 * deletes the person or project the tab shows. Planted in a `PLAIN` database: people Alice and Bob, project Apollo,
 * 30 one-line notes linking only Alice, one linking Alice and Bob, one linking Alice and Apollo and one linking only
 * Bob, written one minute apart and all at least a minute old, the Bob-only note the third newest, so it is among All's
 * first rows and a rename that bumped Alice's notes would move it; Alice is remembered for People and Apollo for
 * Projects. The existence probes can be held ([HeldProbes]). The phone's screen stays on and unlocked (testing.md
 * § 2): the dialog checks find their window by window focus.
 */
@RunWith(AndroidJUnit4::class)
class EntityMenuUiTest : ListPositionComposeTest() {
    private lateinit var probes: HeldProbes

    private var alice = 0L
    private var bob = 0L
    private var apollo = 0L
    private var aliceApollo = 0L
    private var aliceBob = 0L
    private var bobOnly = 0L
    private var aliceOnly = emptyList<Long>()

    init {
        scene.wrapEntityRepository = { real -> HeldProbes(real).also { probes = it } }
    }

    @Test
    fun a_peopleShowsTheMenu_afterSearchAndSettings_withRenameAndDelete() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())

        val search = compose.onNodeWithContentDescription(SEARCH).fetchSemanticsNode().boundsInRoot
        val settings = ui.boundsOf(SETTINGS)
        val menu = ui.boundsOf(ENTITY_MENU)
        assertTrue("Settings after Search", settings.left >= search.right - 1)
        assertTrue("the menu after Settings", menu.left >= settings.right - 1)
        compose.onNodeWithTag(ENTITY_MENU).assertContentDescription(MORE_OPTIONS)

        compose.onNodeWithTag(ENTITY_MENU).performClick()
        ui.awaitText(RENAME_ITEM)
        compose.onNodeWithText(DELETE_ITEM).assertExists()
    }

    @Test
    fun b_noMenu_inTheOtherTabs_whileLoading_inSearchOrSelection_orOnTheBody() {
        startPlanted()
        assertNoMenu("All")
        openTab(Perspective.Date)
        ui.awaitTag(MONTH_GRID)
        assertNoMenu("Calendar")
        openTab(Perspective.Orphan)
        ui.awaitText(NO_DATA)
        assertNoMenu("Orphans")

        // Alice is remembered: the People tab's first decision waits for her existence probe, held here.
        val held = CompletableDeferred<Unit>()
        probes.gate = held
        openTab(Perspective.Individual)
        ui.awaitDescription(LOADING)
        assertNoMenu("Loading")
        probes.gate = null
        held.complete(Unit)
        ui.awaitTag(ENTITY_MENU)
        awaitRow(aliceOnly.first())

        compose.onNodeWithContentDescription(SEARCH).performClick()
        ui.awaitTag(SEARCH_FIELD)
        assertNoMenu("the search bar")
        compose.onNodeWithContentDescription(CLOSE_SEARCH).performClick()
        ui.awaitTag(ENTITY_MENU)
        awaitRow(aliceOnly.first())
        longPress(aliceOnly.first())
        ui.awaitText(selectedTitle(1))
        assertNoMenu("selection mode")
        tapAction(EXIT_SELECTION)
        awaitNoSelection()

        openTitlePicker("Alice")
        compose.onNodeWithTag(PICKER_ALL).performClick()
        awaitPickerBody(alice, bob)
        assertNoMenu("the picker body")
    }

    @Test
    fun c_rename_showsTheNameWithTheCaretAtItsEnd_andRenamesWithoutMovingAnyNote() {
        startPlanted()
        awaitRow(bobOnly)
        val allBefore = displayedRows()
        assertTrue("the Bob-only note is displayed", bobOnly in allBefore)
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())

        openRename()
        ui.awaitText(RENAME_PERSON)
        val field = compose.onNodeWithTag(RENAME_FIELD)
        compose.waitUntil(LONG_WAIT_MS) { runCatching { field.assertIsFocused() }.isSuccess }
        assertEquals("Alice", fieldText())
        assertEquals(TextRange(5), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.awaitDialogIme(shown = true)
        field.performTextInput(" Smith ")
        assertEquals("Alice Smith ", fieldText())
        renameButton().performClick()

        ui.awaitText(PERSON_RENAMED)
        compose.waitUntil(LONG_WAIT_MS) { compose.onAllNodes(titleButton("Alice Smith")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasPaneTitle("Alice Smith")).assertExists()
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE id = ? AND name = 'Alice Smith'", alice))
        openTab(Perspective.All)
        compose.waitUntil(LONG_WAIT_MS) { rowDescription(aliceBob).contains("Alice Smith") }
        assertEquals(allBefore, displayedRows())
        assertEquals(allBefore, onScreenOrder(*allBefore.toLongArray()))
        allBefore.filter { it != bobOnly }.forEach { assertTrue(rowDescription(it), rowDescription(it).contains("Alice Smith")) }
    }

    @Test
    fun d_aRenameOfAScrolledList_keepsEveryRowInItsPlace() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        scrollTo(12)
        val top = topRow()
        val before = displayedRows().associateWith(::rowTop)

        rename("Alicia")

        compose.waitUntil(LONG_WAIT_MS) {
            compose.onAllNodes(titleButton("Alicia")).fetchSemanticsNodes().isNotEmpty() && default.idle
        }
        compose.waitForIdle()
        assertTrue("$top, now ${topRow()}", topRow().samePlaceAs(top))
        before.forEach { (id, y) -> assertTrue("row $id at $y, now ${rowTop(id)}", abs(rowTop(id) - y) <= 1f) }
    }

    @Test
    fun e_aBlankField_disablesRename_andDoneDoesNothing_andCancelWritesNothing() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        openRename()
        ui.awaitText(RENAME_PERSON)
        val field = compose.onNodeWithTag(RENAME_FIELD)

        field.performTextClearance()
        renameButton().assertIsNotEnabled()
        field.performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText(RENAME_PERSON).assertExists()
        field.performTextInput("   ")
        renameButton().assertIsNotEnabled()
        field.performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText(RENAME_PERSON).assertExists()

        awaitDetachedWork { compose.onNode(hasText(CANCEL) and hasClickAction()).performClick() }
        awaitNoText(RENAME_PERSON)
        assertEquals("Alice", storedName(alice))
    }

    @Test
    fun e2_theFirstBackHidesTheKeyboard_theSecondClosesTheDialog_andNothingIsWritten() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        openRename()
        ui.awaitText(RENAME_PERSON)
        compose.onNodeWithTag(RENAME_FIELD).performTextInput("ia")
        compose.awaitDialogIme(shown = true)

        ui.pressBack()
        compose.awaitDialogIme(shown = false)
        compose.onNodeWithText(RENAME_PERSON).assertExists()
        assertEquals("Aliceia", fieldText())

        awaitDetachedWork { ui.pressBack() }
        awaitNoText(RENAME_PERSON)
        assertEquals("Alice", storedName(alice))
    }

    @Test
    fun f_aNameAnotherPersonHolds_inAnotherCase_isRenamedByDone_withoutAHint() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        openTitlePicker("Alice")
        compose.onNodeWithTag(pickerRowTag(bob)).performClick()
        awaitRow(bobOnly)

        openRename()
        ui.awaitText(RENAME_PERSON)
        val field = compose.onNodeWithTag(RENAME_FIELD)
        field.performTextClearance()
        field.performTextInput("alice")
        assertEquals(setOf(RENAME_PERSON, NAME, "alice", CANCEL, RENAME), dialogTexts())
        renameButton().assertIsEnabled()
        field.performImeAction()

        ui.awaitText(PERSON_RENAMED)
        assertEquals("alice", storedName(bob))
        openTitlePicker("alice")
        ui.awaitTag(pickerRowTag(alice))
        ui.awaitTag(pickerRowTag(bob))
    }

    @Test
    fun g_aPasteOf250Characters_leaves200() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        openRename()
        ui.awaitText(RENAME_PERSON)
        val field = compose.onNodeWithTag(RENAME_FIELD)

        field.performTextClearance()
        field.performTextInput("p".repeat(250))

        assertEquals("p".repeat(200), fieldText())
    }

    @Test
    fun h_delete_showsTheImpact_cancelKeepsThePerson_deleteReturnsAnEmptyBody_andOrphansListsHerNotes() {
        startPlanted(rememberAlice = false)
        openTab(Perspective.Individual)
        awaitPickerBody(alice, bob)
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Al")
        compose.waitUntil(LONG_WAIT_MS) { compose.onAllNodesWithTag(pickerRowTag(bob)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        awaitRow(aliceOnly.first())

        openDelete()
        ui.awaitText(DELETE_ALICE)
        compose.onNodeWithText(ALICE_IMPACT).assertExists()
        compose.onNode(hasText(CANCEL) and hasClickAction()).performClick()
        awaitNoText(DELETE_ALICE)
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE id = ?", alice))

        openDelete()
        ui.awaitText(DELETE_ALICE)
        compose.onNode(hasText(DELETE) and hasClickAction()).performClick()

        ui.awaitText(PERSON_DELETED)
        awaitPickerBody(bob)
        assertEquals("", pickerSearchText())
        assertTopPickerRow(bob)
        assertEquals(0L, count("SELECT COUNT(*) FROM persons WHERE id = ?", alice))
        assertNull("nothing remembered", runBlocking { scene.settings.snapshot() }.rememberedPersonId)
        openTab(Perspective.Orphan)
        awaitRow(aliceOnly.first())
        val list = compose.onNodeWithTag(NOTE_LIST).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        aliceOnly.forEach { assertTrue("orphan $it listed", list(it) >= 0) }
        listOf(aliceBob, aliceApollo, bobOnly).forEach { assertEquals("note $it is no orphan", -1, list(it)) }
    }

    @Test
    fun h2_aDeleteOfTheLastName_returnsTheBodyFromItsFirstName() {
        var last = 0L
        startPlanted(rememberAlice = false) {
            last = (1..MORE_PEOPLE).map { person("Person %02d".format(it), createdAt = now - MINUTE) }.last()
        }
        openTab(Perspective.Individual)
        awaitPickerBody(alice, bob)
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(last)
        compose.onNodeWithTag(pickerRowTag(last)).performClick()
        ui.awaitText(NO_DATA)

        openDelete()
        ui.awaitText(DELETE_LAST)
        compose.onNode(hasText(DELETE) and hasClickAction()).performClick()

        ui.awaitText(PERSON_DELETED)
        awaitPickerBody(alice, bob)
        compose.waitForIdle()
        assertTopPickerRow(alice)
    }

    @Test
    fun i_projects_renameAndDeleteTheShownProject() {
        startPlanted()
        openTab(Perspective.Project)
        awaitRow(aliceApollo)

        openRename()
        ui.awaitText(RENAME_PROJECT)
        assertEquals("Apollo", fieldText())
        compose.onNode(hasText(CANCEL) and hasClickAction()).performClick()
        awaitNoText(RENAME_PROJECT)

        openDelete()
        ui.awaitText(DELETE_APOLLO)
        compose.onNodeWithText(APOLLO_IMPACT).assertExists()
        compose.onNode(hasText(DELETE) and hasClickAction()).performClick()
        ui.awaitText(PROJECT_DELETED)
        ui.awaitText(NO_DATA) // the picker body, with no project left
        assertEquals(0L, count("SELECT COUNT(*) FROM projects"))
    }

    @Test
    fun j_theDialogsSurviveRecreation_withoutFocusOrKeyboard_andTheDeleteDialogReadsItsCountsAgain() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        openRename()
        ui.awaitText(RENAME_PERSON)
        compose.onNodeWithTag(RENAME_FIELD).performTextClearance()
        compose.onNodeWithTag(RENAME_FIELD).performTextInput("Alicia")
        compose.awaitDialogIme(shown = true)

        scene.recreateActivity()
        assertRestoredRename()
        scene.recreateAfterProcessDeath()
        assertRestoredRename()
        compose.onNode(hasText(CANCEL) and hasClickAction()).performClick()
        awaitNoText(RENAME_PERSON)

        openDelete()
        ui.awaitText(DELETE_ALICE)
        runBlocking {
            scene.app.container.noteRepository.save(draft(content = WORD, personIds = setOf(alice))).saved()
        }
        scene.recreateAfterProcessDeath()

        ui.awaitText(DELETE_ALICE)
        compose.onNodeWithText(ALICE_IMPACT_AFTER).assertExists()
    }

    @Test
    fun k_theEditorsMenu_stillOffersDeletePersonAndDeleteProject() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly.first())
        compose.onNodeWithTag(rowTag(aliceOnly.first())).performClick()
        ui.awaitTag(EditorUi.CONTENT)

        compose.onNodeWithContentDescription(EditorUi.MORE_OPTIONS).performClick()

        ui.awaitText(EditorUi.DELETE_PERSON)
        compose.onNodeWithText(DELETE_PROJECT_ITEM).assertExists()
    }

    /** The rename dialog after a recreation: "Alicia" kept, the field not focused, no keyboard (DEC-113 (2)). */
    private fun assertRestoredRename() {
        ui.awaitText(RENAME_PERSON)
        compose.awaitDialogIme(shown = false)
        assertEquals("Alicia", fieldText())
        compose.onNodeWithTag(RENAME_FIELD).assertIsNotFocused()
    }

    /** Plants the rows of I-66 with Apollo remembered for Projects and, with [rememberAlice], Alice for People. */
    private fun startPlanted(rememberAlice: Boolean = true, more: TestRows.() -> Unit = {}) {
        startWith(
            settings = {
                if (rememberAlice) setRemembered(EntityKind.Person, alice)
                setRemembered(EntityKind.Project, apollo)
            },
        ) {
            val created = now - 40 * MINUTE
            alice = person("Alice", created)
            bob = person("Bob", created)
            apollo = project("Apollo", created)
            aliceApollo = note("Alice and Apollo", createdAt = now - MINUTE).also {
                linkPerson(it, alice)
                linkProject(it, apollo)
            }
            aliceBob = note("Alice and Bob", createdAt = now - 2 * MINUTE).also {
                linkPerson(it, alice)
                linkPerson(it, bob)
            }
            bobOnly = note("Bob alone", createdAt = now - 3 * MINUTE).also { linkPerson(it, bob) }
            aliceOnly = List(PLANTED) { i ->
                note("Alice $i", createdAt = now - (4 + i) * MINUTE).also { linkPerson(it, alice) }
            }
            more()
        }
    }

    private fun openRename() = menu(RENAME_ITEM)

    private fun openDelete() = menu(DELETE_ITEM)

    private fun menu(item: String) {
        compose.onNodeWithTag(ENTITY_MENU).performClick()
        ui.awaitText(item)
        compose.onNodeWithText(item).performClick()
    }

    /** Renames the shown entity to [name] through the menu and the dialog's button. */
    private fun rename(name: String) {
        openRename()
        ui.awaitTag(RENAME_FIELD)
        compose.onNodeWithTag(RENAME_FIELD).performTextClearance()
        compose.onNodeWithTag(RENAME_FIELD).performTextInput(name)
        renameButton().performClick()
        awaitNoText(RENAME_PERSON)
    }

    private fun renameButton() = compose.onNode(hasText(RENAME) and hasClickAction())

    private fun fieldText(): String = compose.onNodeWithTag(RENAME_FIELD).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Every text the open dialog shows, its field's text included. */
    private fun dialogTexts(): Set<String> = compose.onAllNodes(hasAnyAncestor(isDialog()), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
        .toSet()

    private fun storedName(id: Long): String = (scene.holderState() as HolderState.Open).db
        .openHelper.readableDatabase.query("SELECT name FROM persons WHERE id = ?", arrayOf<Any>(id))
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }

    private fun pickerSearchText(): String = compose.onNodeWithTag(EditorUi.PICKER_SEARCH).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun assertNoMenu(where: String) {
        compose.waitForIdle()
        assertEquals("$where: ⋮", 0, compose.onAllNodesWithTag(ENTITY_MENU).fetchSemanticsNodes().size)
    }

    private fun awaitNoText(text: String) = compose.waitUntil(LONG_WAIT_MS) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun hasPaneTitle(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun SemanticsNodeInteraction.assertContentDescription(text: String) {
        assertEquals(listOf(text), fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
    }

    /** The real repository whose existence probes wait for [gate] while it is set. */
    private class HeldProbes(private val real: EntityRepository) : EntityRepository by real {
        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override fun observe(kind: EntityKind, id: Long): Flow<EntityRef?> = flow {
            gate?.await()
            emitAll(real.observe(kind, id))
        }
    }

    private companion object {
        const val SETTINGS = "action_settings"
        const val SEARCH_FIELD = "search_field"
        const val MORE_PEOPLE = 30

        // Literals copied from ui.md § 8.4, § 8.17 and § 11.
        const val RENAME_ITEM = "Rename…"
        const val DELETE_ITEM = "Delete…"
        const val RENAME_PERSON = "Rename person"
        const val RENAME_PROJECT = "Rename project"
        const val NAME = "Name"
        const val RENAME = "Rename"
        const val PERSON_RENAMED = "Person renamed"
        const val PERSON_DELETED = "Person deleted"
        const val PROJECT_DELETED = "Project deleted"
        const val LOADING = "Loading"
        const val CLOSE_SEARCH = "Close search"
        const val DELETE_PROJECT_ITEM = "Delete project…"
        const val DELETE_ALICE = "Delete person \"Alice\"?"
        const val DELETE_LAST = "Delete person \"Person 30\"?"
        const val DELETE_APOLLO = "Delete project \"Apollo\"?"
        const val ALICE_IMPACT = "This person is linked to 32 notes. 30 of them will have no person or project left and " +
            "will move to Orphans. This cannot be undone."
        const val ALICE_IMPACT_AFTER = "This person is linked to 33 notes. 31 of them will have no person or project " +
            "left and will move to Orphans. This cannot be undone."
        const val APOLLO_IMPACT = "This project is linked to 1 note. That note keeps other links and stays out of " +
            "Orphans. This cannot be undone."
    }
}
